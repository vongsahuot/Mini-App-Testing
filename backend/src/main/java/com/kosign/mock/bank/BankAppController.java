package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bank app backend: login, JWT for KOSIGN, and the payment approval (steps 34–40). */
@RestController
public class BankAppController {
    private final Customer.Repo customers;
    private final Account.Repo accounts;
    private final BankSession.Repo sessions;
    private final BankTxn.Repo txns;
    private final JwtIssuer jwtIssuer;
    private final Ledger ledger;
    private final ResultReporter results;
    private final BankChaos chaos;
    private final Http http;
    private final Trace trace;
    private final String portalUrl, secret;

    public BankAppController(Customer.Repo customers, Account.Repo accounts, BankSession.Repo sessions, BankTxn.Repo txns,
                             JwtIssuer jwtIssuer, Ledger ledger, ResultReporter results, BankChaos chaos, Http http, Trace trace,
                             @Value("${mock.portal-url}") String portalUrl, @Value("${mock.bank-secret}") String secret) {
        this.customers = customers;
        this.accounts = accounts;
        this.sessions = sessions;
        this.txns = txns;
        this.jwtIssuer = jwtIssuer;
        this.ledger = ledger;
        this.results = results;
        this.chaos = chaos;
        this.http = http;
        this.trace = trace;
        this.portalUrl = portalUrl;
        this.secret = secret;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwtIssuer.jwks();
    }

    @PostMapping("/api/v1/bank-app/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        Customer c = customers.findByUsername(Json.required(body, "username"))
                .filter(x -> x.pin.equals(Json.str(body, "pin")))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Wrong username or PIN"));
        BankSession s = new BankSession();
        s.token = "sess_" + Hmac.random(16);
        s.customerId = c.customerId;
        s.deviceId = Json.str(body, "deviceId");
        s.deviceSecret = Hmac.random(16);
        s.createdAt = Instant.now();
        sessions.save(s);
        return Json.obj("sessionToken", s.token, "customerId", c.customerId, "name", c.name, "phone", c.phone,
                "deviceSecret", s.deviceSecret, "accounts", accountViews(c.customerId));
    }

    @PostMapping("/api/v1/bank-app/jwt")
    public Map<String, Object> jwt(@RequestHeader(value = "Authorization", required = false) String auth) {
        Customer c = customers.findById(session(auth).customerId).orElseThrow();
        return Json.obj("jwt", jwtIssuer.issue(c), "expiresIn", jwtIssuer.ttlSeconds());
    }

    @GetMapping("/api/v1/bank-app/accounts")
    public List<Map<String, Object>> accounts(@RequestHeader(value = "Authorization", required = false) String auth) {
        return accountViews(session(auth).customerId);
    }

    @PostMapping("/api/v1/bank-app/payments/{paymentId}/approve")
    public Map<String, Object> approve(@PathVariable String paymentId, @RequestBody JsonNode body,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        BankSession s = session(auth);
        String debitAccount = Json.required(body, "debitAccount");
        trace.record(34, "Approval received: paymentId, debitAccount, signed approval", "debit " + debitAccount, paymentId);

        BankTxn done = txns.findFirstByPaymentIdAndTypeAndStatus(paymentId, BankTxn.PAYMENT, BankTxn.SUCCESS).orElse(null);
        if (done != null) return outcome(paymentId, "PAID", done.txnId, done.amount, done.currency, null);

        Account account = accounts.findById(debitAccount).filter(a -> s.customerId.equals(a.customerId)).orElse(null);
        if (account == null) return outcome(paymentId, "FAILED", null, null, null, "INVALID_ACCOUNT");

        // 35–37: lock the payment at KOSIGN (moves it PENDING → PROCESSING exactly once)
        Http.Resp lock = http.signed(HttpMethod.POST, portalUrl + "/api/v1/bank/payments/" + paymentId + "/lock",
                Json.obj("customerId", s.customerId), ResultReporter.CLIENT_ID, secret);
        if (!lock.ok()) {
            trace.record(37, "Lock rejected by KOSIGN", lock.error() + ": " + Json.str(lock.body(), "message"), paymentId);
            return outcome(paymentId, "FAILED", null, null, null, "LOCK_REJECTED");
        }
        BigDecimal amount = Json.money(lock.body(), "amount");
        String currency = Json.str(lock.body(), "currency");
        String settlement = Json.str(lock.body(), "settlementAccount");
        trace.record(37, "Locked: amount, currency, settlement account", amount + " " + currency + " → " + settlement, paymentId);

        // 38: verify the device signature and balance, then move the money in one core-banking transaction
        String reason = null;
        if (!Hmac.equal(Hmac.hex(s.deviceSecret, paymentId), Json.str(body, "signature"))) reason = "INVALID_SIGNATURE";
        else if (chaos.failDebit) reason = "DEBIT_FAILED";
        else if (account.balance.compareTo(amount) < 0) reason = "INSUFFICIENT_FUNDS";

        BankTxn txn = reason == null
                ? ledger.transfer(BankTxn.PAYMENT, paymentId, null, debitAccount, settlement, amount, currency)
                : ledger.failed(BankTxn.PAYMENT, paymentId, null, debitAccount, settlement, amount, currency, reason);
        trace.record(38, reason == null
                        ? "Signature + balance ok → debit customer + credit settlement account in 1 core-banking txn"
                        : "Rejected: " + reason,
                txn.txnId, paymentId);

        String status = reason == null ? "PAID" : "FAILED";
        results.report(paymentId, status, txn.txnId, reason); // 39
        return outcome(paymentId, status, reason == null ? txn.txnId : null, amount, currency, reason); // 40
    }

    private static Map<String, Object> outcome(String paymentId, String status, String txnId, BigDecimal amount, String currency, String reason) {
        return Json.obj("paymentId", paymentId, "status", status, "bankTxnId", txnId, "amount", amount, "currency", currency, "reason", reason);
    }

    private List<Map<String, Object>> accountViews(String customerId) {
        return accounts.findByCustomerIdOrderByAccountNo(customerId).stream()
                .map(a -> Json.obj("accountNo", a.accountNo, "name", a.name, "balance", a.balance, "currency", a.currency))
                .toList();
    }

    private BankSession session(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw ApiException.unauthorized("MISSING_TOKEN", "Bearer session token required");
        return sessions.findById(auth.substring(7).trim())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_SESSION", "Unknown session, please log in again"));
    }
}

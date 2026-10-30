package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class BankAdminController {
    private final Account.Repo accounts;
    private final BankTxn.Repo txns;
    private final BankSession.Repo sessions;
    private final Customer.Repo customers;
    private final BankSeeder seeder;
    private final BankChaos chaos;
    private final Http http;
    private final Trace trace;
    private final String portalUrl, secret;

    public BankAdminController(Account.Repo accounts, BankTxn.Repo txns, BankSession.Repo sessions, Customer.Repo customers,
                               BankSeeder seeder, BankChaos chaos, Http http, Trace trace,
                               @Value("${mock.portal-url}") String portalUrl, @Value("${mock.bank-secret}") String secret) {
        this.accounts = accounts;
        this.txns = txns;
        this.sessions = sessions;
        this.customers = customers;
        this.seeder = seeder;
        this.chaos = chaos;
        this.http = http;
        this.trace = trace;
        this.portalUrl = portalUrl;
        this.secret = secret;
    }

    @GetMapping("/accounts")
    public List<Account> accounts() {
        return accounts.findAllByOrderByAccountNo();
    }

    @GetMapping("/transactions")
    public List<BankTxn> transactions() {
        return txns.findAllByOrderByCreatedAtDesc();
    }

    /** Wipes transactions and sessions and restores seed balances. Reset the portal too, or settlement will mismatch. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        txns.deleteAll();
        sessions.deleteAll();
        accounts.deleteAll();
        customers.deleteAll();
        seeder.run(null);
        trace.clear();
        chaos.dropResultCallback = chaos.failDebit = chaos.failRefund = chaos.settlementMismatch = false;
        return Json.obj("ok", true);
    }

    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        return Json.obj("dropResultCallback", chaos.dropResultCallback, "failDebit", chaos.failDebit,
                "failRefund", chaos.failRefund, "settlementMismatch", chaos.settlementMismatch);
    }

    @PutMapping("/chaos")
    public Map<String, Object> setChaos(@RequestBody JsonNode b) {
        if (b.has("dropResultCallback")) chaos.dropResultCallback = b.get("dropResultCallback").asBoolean();
        if (b.has("failDebit")) chaos.failDebit = b.get("failDebit").asBoolean();
        if (b.has("failRefund")) chaos.failRefund = b.get("failRefund").asBoolean();
        if (b.has("settlementMismatch")) chaos.settlementMismatch = b.get("settlementMismatch").asBoolean();
        return chaos();
    }

    /** Step 44: send the settlement file (every successful bankTxnId) to KOSIGN and return its reconciliation. */
    @PostMapping("/settlement/run")
    public ResponseEntity<JsonNode> runSettlement() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (BankTxn t : txns.findByStatusOrderByCreatedAt(BankTxn.SUCCESS)) {
            BigDecimal amount = t.amount;
            if (chaos.settlementMismatch && entries.isEmpty()) amount = amount.add(new BigDecimal("0.01"));
            entries.add(Json.obj("bankTxnId", t.txnId, "type", t.type, "paymentId", t.paymentId, "refundId", t.refundId,
                    "amount", amount, "status", t.status));
        }
        Http.Resp r = http.signed(HttpMethod.POST, portalUrl + "/api/v1/bank/settlement-files",
                Json.obj("date", LocalDate.now().toString(), "entries", entries), ResultReporter.CLIENT_ID, secret);
        return ResponseEntity.status(r.ok() ? 200 : 502).body(r.body());
    }
}

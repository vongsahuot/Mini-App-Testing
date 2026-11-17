package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

/** Test console endpoints for the bank, and Core Banking's daily settlement file (4:17). No auth: local mock. */
@RestController
@RequestMapping("/api/v1/admin")
public class BankAdminController {
    private final Account.Repo accounts;
    private final BankTxn.Repo txns;
    private final Hold.Repo holds;
    private final Voided.Repo voids;
    private final BankSession.Repo sessions;
    private final Customer.Repo customers;
    private final Notification.Repo notifications;
    private final PaymentAuth.Repo auths;
    private final BankSeeder seeder;
    private final BankChaos chaos;
    private final MasClient mas;
    private final Trace trace;

    public BankAdminController(Account.Repo accounts, BankTxn.Repo txns, Hold.Repo holds, Voided.Repo voids,
                               BankSession.Repo sessions, Customer.Repo customers, Notification.Repo notifications,
                               PaymentAuth.Repo auths, BankSeeder seeder, BankChaos chaos, MasClient mas, Trace trace) {
        this.accounts = accounts;
        this.txns = txns;
        this.holds = holds;
        this.voids = voids;
        this.sessions = sessions;
        this.customers = customers;
        this.notifications = notifications;
        this.auths = auths;
        this.seeder = seeder;
        this.chaos = chaos;
        this.mas = mas;
        this.trace = trace;
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return accounts.findAllByOrderByAccountNo().stream().map(a -> Json.obj("accountNo", a.accountNo, "customerId", a.customerId,
                "name", a.name, "balance", a.balance, "held", a.held, "available", a.available(), "currency", a.currency)).toList();
    }

    @GetMapping("/transactions")
    public List<BankTxn> transactions() {
        return txns.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/holds")
    public List<Hold> holds() {
        return holds.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/customers")
    public List<Map<String, Object>> customers() {
        return customers.findAll().stream().map(c -> Json.obj("customerId", c.customerId, "username", c.username, "name", c.name,
                "segment", c.segment, "authFailures", c.authFailures, "authLocked", c.authLocked)).toList();
    }

    @PostMapping("/customers/{customerId}/unlock")
    public Map<String, Object> unlock(@PathVariable String customerId) {
        Customer c = customers.findById(customerId).orElseThrow(() -> ApiException.notFound("No customer " + customerId));
        c.authLocked = false;
        c.authFailures = 0;
        customers.save(c);
        return Json.obj("customerId", customerId, "authLocked", false);
    }

    @GetMapping("/notifications")
    public List<Notification> notifications() {
        return notifications.findAllByOrderByIdDesc();
    }

    /** Wipes transactions, holds, sessions and notifications and restores seed balances. Reset the portal too. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        txns.deleteAll();
        holds.deleteAll();
        voids.deleteAll();
        auths.deleteAll();
        notifications.deleteAll();
        sessions.deleteAll();
        accounts.deleteAll();
        customers.deleteAll();
        seeder.run(null);
        trace.clear();
        chaos.reset();
        return Json.obj("ok", true);
    }

    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        return chaos.view();
    }

    @PutMapping("/chaos")
    public Map<String, Object> setChaos(@RequestBody JsonNode b) {
        chaos.apply(b);
        return chaos.view();
    }

    /** Core Banking sends the settlement file (every posted payment and refund) to Mini App Service. */
    @PostMapping("/settlement/run")
    public ResponseEntity<JsonNode> runSettlement() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (BankTxn t : txns.findByStatusOrderByCreatedAt(BankTxn.SUCCESS)) {
            BigDecimal amount = t.amount;
            if (chaos.settlementMismatch && entries.isEmpty()) amount = amount.add(new BigDecimal("0.01"));
            entries.add(Json.obj("bankTxnId", t.txnId, "type", t.type, "paymentRef", t.paymentRef, "refundId", t.refundId,
                    "amount", amount, "status", t.status));
        }
        Http.Resp r = mas.settlement(Json.obj("date", LocalDate.now().toString(), "entries", entries));
        return ResponseEntity.status(r.ok() ? 200 : 502).body(r.body());
    }

    /** Daily at 01:00 (step 4:17). */
    @Scheduled(cron = "0 0 1 * * *")
    public void dailySettlement() {
        runSettlement();
    }
}

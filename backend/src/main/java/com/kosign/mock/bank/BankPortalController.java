package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/** KOSIGN → bank: status checks for recovery (41) and refunds (57–58). Signed with the shared bank secret. */
@RestController
@RequestMapping("/api/v1/bank")
public class BankPortalController {
    private static final String PORTAL_CLIENT_ID = "kosign-portal";

    private final Signatures signatures;
    private final Http http;
    private final BankTxn.Repo txns;
    private final Ledger ledger;
    private final BankChaos chaos;
    private final Trace trace;
    private final String secret, settlementAccount;

    public BankPortalController(Signatures signatures, Http http, BankTxn.Repo txns, Ledger ledger, BankChaos chaos, Trace trace,
                                @Value("${mock.bank-secret}") String secret,
                                @Value("${mock.settlement-account}") String settlementAccount) {
        this.signatures = signatures;
        this.http = http;
        this.txns = txns;
        this.ledger = ledger;
        this.chaos = chaos;
        this.trace = trace;
        this.secret = secret;
        this.settlementAccount = settlementAccount;
    }

    @GetMapping("/payments/{paymentId}/status")
    public Map<String, Object> status(HttpServletRequest req, @PathVariable String paymentId) {
        verify(req, null);
        trace.record(41, "GET /bank/payments/{paymentId}/status (mTLS)", null, paymentId);
        BankTxn t = txns.findFirstByPaymentIdAndTypeAndStatus(paymentId, BankTxn.PAYMENT, BankTxn.SUCCESS)
                .or(() -> txns.findFirstByPaymentIdAndTypeOrderByCreatedAtDesc(paymentId, BankTxn.PAYMENT))
                .orElse(null);
        String status = t == null ? "NOT_FOUND" : BankTxn.SUCCESS.equals(t.status) ? "PAID" : "FAILED";
        return Json.obj("paymentId", paymentId, "status", status, "bankTxnId", t == null ? null : t.txnId);
    }

    @PostMapping("/refunds")
    public synchronized Map<String, Object> refund(HttpServletRequest req, @RequestBody(required = false) String raw) {
        verify(req, raw);
        JsonNode b = http.parse(raw);
        String refundId = Json.required(b, "refundId");
        String paymentId = Json.required(b, "paymentId");
        BigDecimal amount = Json.money(b, "amount");
        trace.record(57, "POST /bank/refunds (mTLS + signature)", refundId + ", " + amount, paymentId);

        BankTxn existing = txns.findFirstByRefundId(refundId).orElse(null);
        if (existing != null) return view(refundId, existing);

        BankTxn payment = txns.findFirstByPaymentIdAndTypeAndStatus(paymentId, BankTxn.PAYMENT, BankTxn.SUCCESS).orElse(null);
        String currency = Json.str(b, "currency");
        BankTxn t;
        if (payment == null) {
            t = ledger.failed(BankTxn.REFUND, paymentId, refundId, settlementAccount, null, amount, currency, "PAYMENT_NOT_FOUND");
        } else if (chaos.failRefund) {
            t = ledger.failed(BankTxn.REFUND, paymentId, refundId, settlementAccount, payment.debitAccount, amount, currency, "REFUND_REJECTED");
        } else {
            t = ledger.transfer(BankTxn.REFUND, paymentId, refundId, settlementAccount, payment.debitAccount, amount, currency);
        }
        trace.record(58, BankTxn.SUCCESS.equals(t.status)
                ? "Debit settlement account + credit customer in 1 core-banking txn"
                : "Refund rejected: " + t.reason, t.txnId, paymentId);
        return view(refundId, t);
    }

    private static Map<String, Object> view(String refundId, BankTxn t) {
        return Json.obj("refundId", refundId, "status", BankTxn.SUCCESS.equals(t.status) ? "REFUNDED" : "FAILED",
                "refundTxnId", t.txnId, "reason", t.reason);
    }

    private void verify(HttpServletRequest req, String body) {
        signatures.verify(req, body, id -> PORTAL_CLIENT_ID.equals(id) ? secret : null);
    }
}

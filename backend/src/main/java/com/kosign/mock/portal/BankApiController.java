package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Bank server → KOSIGN (mTLS + signature in production; HMAC signature here). */
@RestController
@RequestMapping("/api/v1/bank")
public class BankApiController {
    private final Signatures signatures;
    private final Http http;
    private final Payment.Repo payments;
    private final Partner.Repo partners;
    private final PaymentService paymentService;
    private final SettlementService settlement;
    private final Trace trace;
    private final String bankSecret;

    public BankApiController(Signatures signatures, Http http, Payment.Repo payments, Partner.Repo partners,
                             PaymentService paymentService, SettlementService settlement, Trace trace,
                             @Value("${mock.bank-secret}") String bankSecret) {
        this.signatures = signatures;
        this.http = http;
        this.payments = payments;
        this.partners = partners;
        this.paymentService = paymentService;
        this.settlement = settlement;
        this.trace = trace;
        this.bankSecret = bankSecret;
    }

    @PostMapping("/payments/{paymentId}/lock")
    public Map<String, Object> lock(HttpServletRequest req, @PathVariable String paymentId, @RequestBody(required = false) String raw) {
        verify(req, raw);
        trace.record(35, "POST /bank/payments/{paymentId}/lock (mTLS + signature)", null, paymentId);
        String customerId = Json.required(http.parse(raw), "customerId");
        if (payments.lock(paymentId, customerId, Instant.now()) == 0) {
            String reason = payments.findById(paymentId).map(p ->
                    !p.customerId.equals(customerId) ? "different customer"
                            : !Payment.PENDING.equals(p.status) ? "status is " + p.status : "payment expired").orElse("unknown payment");
            trace.record(36, "Atomic check failed → lock rejected", reason, paymentId);
            throw ApiException.conflict("LOCK_REJECTED", reason);
        }
        trace.record(36, "Atomic check: PENDING + not expired + same customer → PROCESSING", null, paymentId);
        Payment p = payments.findById(paymentId).orElseThrow();
        Partner partner = partners.findById(p.clientId).orElseThrow();
        return Json.obj("paymentId", p.paymentId, "amount", p.amount, "currency", p.currency,
                "settlementAccount", partner.settlementAccount, "merchantName", p.merchantName);
    }

    @PostMapping("/payments/{paymentId}/result")
    public Map<String, Object> result(HttpServletRequest req, @PathVariable String paymentId, @RequestBody(required = false) String raw) {
        verify(req, raw);
        JsonNode b = http.parse(raw);
        String status = Json.required(b, "status");
        String txn = Json.str(b, "bankTxnId");
        trace.record(39, "POST /bank/payments/{paymentId}/result: " + status, txn, paymentId);
        if (!paymentService.applyBankOutcome(paymentId, status, txn)) {
            Payment p = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " not found"));
            trace.record(0, "Duplicate result ignored (idempotent by bankTxnId)", "payment already " + p.status, paymentId);
        }
        return Json.obj("ok", true);
    }

    @PostMapping("/settlement-files")
    public Map<String, Object> settlementFile(HttpServletRequest req, @RequestBody(required = false) String raw) {
        verify(req, raw);
        JsonNode file = http.parse(raw);
        trace.record(44, "Settlement file: every bankTxnId, amount, status", file.path("entries").size() + " entries",
                "settlement " + Json.str(file, "date"));
        return settlement.reconcile(file);
    }

    private void verify(HttpServletRequest req, String body) {
        signatures.verify(req, body, id -> PaymentService.BANK_CLIENT_ID.equals(id) ? bankSecret : null);
    }
}

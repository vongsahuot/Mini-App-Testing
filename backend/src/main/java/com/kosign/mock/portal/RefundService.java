package com.kosign.mock.portal;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Partner-initiated full or partial refunds (steps 55–60), idempotent by refundId. */
@Service
public class RefundService {
    private final Refund.Repo refunds;
    private final Payment.Repo payments;
    private final WebhookService webhooks;
    private final Http http;
    private final Trace trace;
    private final String bankUrl, bankSecret;

    public RefundService(Refund.Repo refunds, Payment.Repo payments, WebhookService webhooks, Http http, Trace trace,
                         @Value("${mock.bank-url}") String bankUrl, @Value("${mock.bank-secret}") String bankSecret) {
        this.refunds = refunds;
        this.payments = payments;
        this.webhooks = webhooks;
        this.http = http;
        this.trace = trace;
        this.bankUrl = bankUrl;
        this.bankSecret = bankSecret;
    }

    public Map<String, Object> view(Refund r) {
        Payment p = payments.findById(r.paymentId).orElse(null);
        return Json.obj("refundId", r.refundId, "paymentId", r.paymentId, "amount", r.amount, "status", r.status,
                "refundTxnId", r.refundTxnId, "reason", r.reason,
                "paymentStatus", p == null ? null : p.status,
                "paymentRefundedAmount", p == null ? null : p.refundedAmount);
    }

    public synchronized Refund request(String clientId, String paymentId, String refundId, BigDecimal amount, String reason) {
        Refund existing = refunds.findById(refundId).orElse(null);
        if (existing != null) {
            if (!existing.clientId.equals(clientId) || !existing.paymentId.equals(paymentId)) {
                throw ApiException.conflict("REFUND_ID_CONFLICT", "refundId already used for another payment");
            }
            trace.record(56, "Same refundId → return existing refund", existing.status, paymentId);
            return existing;
        }
        Payment p = payments.findById(paymentId).filter(x -> x.clientId.equals(clientId))
                .orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " not found"));
        if (!Payment.PAID.equals(p.status) && !Payment.PARTIALLY_REFUNDED.equals(p.status)) {
            trace.record(56, "Refund rejected", "payment is " + p.status, paymentId);
            throw ApiException.conflict("NOT_REFUNDABLE", "Payment is " + p.status);
        }
        BigDecimal pending = refunds.findByPaymentId(paymentId).stream()
                .filter(r -> Refund.PENDING.equals(r.status)).map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = p.amount.subtract(p.refundedAmount).subtract(pending);
        if (amount == null) amount = remaining;
        if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            trace.record(56, "Refund rejected", "amount " + amount + " > remaining " + remaining, paymentId);
            throw ApiException.conflict("AMOUNT_EXCEEDS_REMAINING", "Refund amount must be > 0 and <= " + remaining);
        }

        Instant now = Instant.now();
        Refund r = new Refund();
        r.refundId = refundId;
        r.clientId = clientId;
        r.paymentId = paymentId;
        r.amount = amount;
        r.reason = reason;
        r.status = Refund.PENDING;
        r.createdAt = now;
        r.updatedAt = now;
        refunds.save(r);
        trace.record(56, "Idempotent by refundId, amount ≤ remaining → REFUND_PENDING", amount + " of " + remaining, paymentId);
        return process(r);
    }

    /** Ask the bank to move the money back; on a transport failure the refund stays pending and is retried. */
    synchronized Refund process(Refund r) {
        Payment p = payments.findById(r.paymentId).orElseThrow();
        Http.Resp resp = http.signed(HttpMethod.POST, bankUrl + "/api/v1/bank/refunds",
                Json.obj("refundId", r.refundId, "paymentId", p.paymentId, "bankTxnId", p.bankTxnId,
                        "amount", r.amount, "currency", p.currency),
                WebhookService.SENDER_ID, bankSecret);
        r.updatedAt = Instant.now();
        if (!resp.ok()) {
            refunds.save(r);
            trace.record(0, "Bank refund call failed, will retry", resp.error(), p.paymentId);
            return r;
        }
        String status = Json.str(resp.body(), "status");
        r.refundTxnId = Json.str(resp.body(), "refundTxnId");
        trace.record(59, "Bank answered " + status, r.refundTxnId, p.paymentId);
        if (Refund.REFUNDED.equals(status)) {
            r.status = Refund.REFUNDED;
            p.refundedAmount = p.refundedAmount.add(r.amount);
            p.status = p.refundedAmount.compareTo(p.amount) >= 0 ? Payment.REFUNDED : Payment.PARTIALLY_REFUNDED;
            p.updatedAt = r.updatedAt;
            payments.save(p);
        } else {
            r.status = Refund.FAILED;
        }
        refunds.save(r);
        webhooks.enqueue(r.clientId, Refund.REFUNDED.equals(r.status) ? "refund.succeeded" : "refund.failed", p.paymentId,
                Json.obj("refundId", r.refundId, "paymentId", p.paymentId, "orderId", p.orderId, "amount", r.amount,
                        "status", r.status, "refundTxnId", r.refundTxnId,
                        "paymentStatus", p.status, "paymentRefundedAmount", p.refundedAmount));
        return r;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void retryPending() {
        for (Refund r : refunds.findByStatusAndUpdatedAtBefore(Refund.PENDING, Instant.now().minusSeconds(10))) {
            process(r);
        }
    }
}

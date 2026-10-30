package com.kosign.mock.portal;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Payment outcomes, stuck-payment recovery (steps 41–43) and PENDING expiry. */
@Service
public class PaymentService {
    public static final String BANK_CLIENT_ID = "kb-bank";

    private final Payment.Repo payments;
    private final WebhookService webhooks;
    private final Http http;
    private final Trace trace;
    private final String bankUrl, bankSecret;
    private final Duration stuckAfter, lockTimeout;

    public PaymentService(Payment.Repo payments, WebhookService webhooks, Http http, Trace trace,
                          @Value("${mock.bank-url}") String bankUrl,
                          @Value("${mock.bank-secret}") String bankSecret,
                          @Value("${mock.recovery.stuck-after-seconds}") long stuckAfterSeconds,
                          @Value("${mock.recovery.lock-timeout-seconds}") long lockTimeoutSeconds) {
        this.payments = payments;
        this.webhooks = webhooks;
        this.http = http;
        this.trace = trace;
        this.bankUrl = bankUrl;
        this.bankSecret = bankSecret;
        this.stuckAfter = Duration.ofSeconds(stuckAfterSeconds);
        this.lockTimeout = Duration.ofSeconds(lockTimeoutSeconds);
    }

    public static Map<String, Object> partnerView(Payment p) {
        return Json.obj("paymentId", p.paymentId, "orderId", p.orderId, "amount", p.amount, "currency", p.currency,
                "status", p.status, "bankTxnId", p.bankTxnId, "refundedAmount", p.refundedAmount,
                "createdAt", p.createdAt, "expiresAt", p.expiresAt);
    }

    /**
     * Applies the bank's final answer, from the result callback (39) or a recovery status check (43).
     * Returns false when the payment was not PROCESSING (duplicate or late answer).
     */
    public boolean applyBankOutcome(String paymentId, String bankStatus, String bankTxnId) {
        String to = Payment.PAID.equals(bankStatus) ? Payment.PAID : Payment.FAILED;
        if (payments.settle(paymentId, to, bankTxnId, Instant.now()) == 0) return false;
        Payment p = payments.findById(paymentId).orElseThrow();
        webhooks.enqueue(p.clientId, Payment.PAID.equals(to) ? "payment.paid" : "payment.failed", p.paymentId,
                Json.obj("paymentId", p.paymentId, "orderId", p.orderId, "amount", p.amount, "currency", p.currency,
                        "status", to, "bankTxnId", bankTxnId));
        return true;
    }

    @Scheduled(fixedDelayString = "${mock.recovery.interval-ms}", initialDelay = 10_000)
    public void scheduledRecovery() {
        recover(stuckAfter);
    }

    /** Ask the bank about every payment stuck in PROCESSING; never fail one without the bank's answer. */
    public synchronized int recover(Duration olderThan) {
        Instant now = Instant.now();
        List<Payment> stuck = payments.findByStatusAndLockedAtBefore(Payment.PROCESSING, now.minus(olderThan));
        for (Payment p : stuck) {
            long age = Duration.between(p.lockedAt, now).toSeconds();
            trace.record(0, "Recovery loop: PROCESSING for " + age + " s → ask bank", null, p.paymentId);
            Http.Resp r = http.signed(HttpMethod.GET, bankUrl + "/api/v1/bank/payments/" + p.paymentId + "/status",
                    null, WebhookService.SENDER_ID, bankSecret);
            if (!r.ok()) {
                trace.record(0, "Bank status check failed, keep PROCESSING", r.error(), p.paymentId);
                continue;
            }
            String status = Json.str(r.body(), "status");
            String txn = Json.str(r.body(), "bankTxnId");
            trace.record(42, "Bank status: " + status, txn, p.paymentId);
            if (Payment.PAID.equals(status) || Payment.FAILED.equals(status)) {
                boolean applied = applyBankOutcome(p.paymentId, status, txn);
                trace.record(43, "Apply bank's answer → " + status, applied ? "webhook queued" : "already settled", p.paymentId);
            } else if (p.lockedAt.plus(lockTimeout).isBefore(now)) {
                applyBankOutcome(p.paymentId, Payment.FAILED, null);
                trace.record(43, "NOT_FOUND after lock timeout → FAILED", null, p.paymentId);
            } else {
                trace.record(43, "NOT_FOUND within lock timeout → keep PROCESSING", null, p.paymentId);
            }
        }
        return stuck.size();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void expirePending() {
        Instant now = Instant.now();
        for (Payment p : payments.findByStatusAndExpiresAtBefore(Payment.PENDING, now)) {
            if (payments.transition(p.paymentId, Payment.PENDING, Payment.EXPIRED, now) == 1) {
                trace.record(0, "PENDING payment expired (10 min TTL)", null, p.paymentId);
            }
        }
    }
}

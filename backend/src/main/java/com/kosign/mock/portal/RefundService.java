package com.kosign.mock.portal;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Partner-initiated refunds (4:2–4:10), idempotent by refundId and never more than what is left to refund,
 * plus the refund recovery job (4:F1–F9) for refunds stuck in REFUND_PENDING.
 */
@Service
public class RefundService {
    private final Refund.Repo refunds;
    private final Payment.Repo payments;
    private final BankClients bank;
    private final Ops ops;
    private final Trace trace;
    private final Duration stuckAfter, safeWindow, alertAfter;

    public RefundService(Refund.Repo refunds, Payment.Repo payments, BankClients bank, Ops ops, Trace trace,
                         @Value("${mock.recovery.stuck-after-seconds}") long stuckAfter,
                         @Value("${mock.recovery.not-found-safe-window-seconds}") long safeWindow,
                         @Value("${mock.recovery.alert-after-seconds}") long alertAfter) {
        this.refunds = refunds;
        this.payments = payments;
        this.bank = bank;
        this.ops = ops;
        this.trace = trace;
        this.stuckAfter = Duration.ofSeconds(stuckAfter);
        this.safeWindow = Duration.ofSeconds(safeWindow);
        this.alertAfter = Duration.ofSeconds(alertAfter);
    }

    public Map<String, Object> view(Refund r) {
        Payment p = payments.findById(r.paymentRef).orElse(null);
        BigDecimal refunded = p == null ? BigDecimal.ZERO : p.refundedAmount;
        return Json.obj("refundId", r.refundId, "paymentRef", r.paymentRef, "amount", r.amount, "status", r.status,
                "refundTxnId", r.refundTxnId, "reason", r.reason, "refundedTotal", refunded,
                "remaining", p == null ? null : p.amount.subtract(refunded).subtract(pending(p.paymentRef)));
    }

    private BigDecimal pending(String paymentRef) {
        return refunds.findByPaymentRef(paymentRef).stream().filter(x -> Refund.PENDING.equals(x.status))
                .map(x -> x.amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    // ---------- 4:2–4:10 ----------

    public synchronized Refund request(Partner caller, String paymentRef, String refundId, BigDecimal amount, String reason) {
        trace.record("MAS", "4:2", null, "Forward API (partner gateway: IP allowlist + mTLS, rate limit)", refundId + ", " + amount, paymentRef);
        Refund existing = refunds.findById(refundId).orElse(null);
        if (existing != null) {
            if (!existing.clientId.equals(caller.clientId) || !existing.paymentRef.equals(paymentRef)) {
                throw ApiException.conflict("REFUND_ID_CONFLICT", "refundId already used for another payment");
            }
            trace.record("MAS", "4:3", null, "Same refundId → return its result", existing.status, paymentRef);
            return existing;
        }
        Payment p = payments.findById(paymentRef).filter(x -> x.clientId.equals(caller.clientId)).orElse(null);
        BigDecimal remaining = p == null ? BigDecimal.ZERO : p.amount.subtract(p.refundedAmount).subtract(pending(paymentRef));
        if (amount == null) amount = remaining;
        ApiException problem = p == null ? ApiException.notFound("No payment " + paymentRef + " for " + caller.clientId)
                : !Payment.SUCCESS.equals(p.status) ? ApiException.conflict("NOT_REFUNDABLE", "Payment is " + p.status)
                : amount.signum() <= 0 || amount.compareTo(remaining) > 0
                ? ApiException.conflict("AMOUNT_EXCEEDS_REMAINING", "Refund must be > 0 and ≤ " + remaining) : null;
        if (problem != null) {
            trace.record("MAS", "4:3", null, "Payment SUCCESS? amount ≤ remaining? No", problem.getMessage(), paymentRef);
            throw problem;
        }
        Instant now = Instant.now();
        Refund r = new Refund();
        r.refundId = refundId;
        r.clientId = caller.clientId;
        r.paymentRef = paymentRef;
        r.customerId = p.customerId;
        r.amount = amount;
        r.currency = p.currency;
        r.reason = reason;
        r.status = Refund.PENDING;
        r.createdAt = now;
        r.updatedAt = now;
        refunds.save(r);
        trace.record("MAS", "4:3", null, "Same refundId → return its result. Payment SUCCESS? amount ≤ remaining? → save REFUND_PENDING",
                amount + " of " + remaining + " remaining", paymentRef);

        Http.Resp resp = bank.lg("refunds", Json.obj("refundId", refundId, "paymentRef", paymentRef, "bankTxnId", p.bankTxnId,
                "amount", amount, "currency", p.currency));
        if (!resp.ok()) {
            trace.record("MAS", "4:7", null, "No answer from Local Gateway → stays REFUND_PENDING", resp.error(), paymentRef);
            return r;
        }
        String status = Json.str(resp.body(), "status");
        trace.record("MAS", "4:7", null, "REFUNDED + refundTxnId / FAILED", status + " " + Json.str(resp.body(), "refundTxnId"), paymentRef);
        finish(r, status, Json.str(resp.body(), "refundTxnId"), "4:8", "Save refund status (REFUNDED / FAILED) + audit log", "4:9", "4:10");
        return refunds.findById(refundId).orElseThrow();
    }

    public Refund status(Partner caller, String paymentRef, String refundId) {
        trace.record("MAS", "4:14", null, "Forward API (partner gateway: IP allowlist + mTLS, rate limit)", refundId, paymentRef);
        return refunds.findById(refundId).filter(r -> r.clientId.equals(caller.clientId) && r.paymentRef.equals(paymentRef))
                .orElseThrow(() -> ApiException.notFound("No refund " + refundId + " for payment " + paymentRef));
    }

    /** Saves the final refund status once (+ audit); a REFUNDED refund adds to the payment's refunded total and tells the customer. */
    private synchronized boolean finish(Refund r, String status, String refundTxnId, String saveStep, String saveTitle,
                                        String notifyStep, String pushStep) {
        Refund fresh = refunds.findById(r.refundId).orElseThrow();
        if (!Refund.PENDING.equals(fresh.status)) return false;
        boolean ok = Refund.REFUNDED.equals(status);
        fresh.status = ok ? Refund.REFUNDED : Refund.FAILED;
        fresh.refundTxnId = refundTxnId;
        fresh.updatedAt = Instant.now();
        refunds.save(fresh);
        Payment p = payments.findById(fresh.paymentRef).orElseThrow();
        if (ok) {
            p.refundedAmount = p.refundedAmount.add(fresh.amount);
            p.updatedAt = Instant.now();
            payments.save(p);
        }
        ops.audit("mini-app-service", "REFUND_" + fresh.status, fresh.refundId, fresh.amount + " " + fresh.currency + " on " + fresh.paymentRef
                + (refundTxnId == null ? "" : " (" + refundTxnId + ")"));
        trace.record("MAS", saveStep, null, saveTitle, fresh.status + (ok ? ", refunded total " + p.refundedAmount : ", amount freed"), fresh.paymentRef);
        if (ok) {
            bank.notifyCustomer(fresh.customerId, "REFUND", "Refund received", fresh.amount + " " + fresh.currency + " from " + p.merchantName,
                    fresh.paymentRef, notifyStep, "4:9".equals(notifyStep) ? "Notify customer (refund)" : "Notify customer (refund), if REFUNDED", pushStep);
        }
        return true;
    }

    // ---------- refund recovery (4:F1–F9) ----------

    @Scheduled(fixedDelayString = "${mock.recovery.interval-ms}", initialDelay = 14_000)
    public void scheduledRecovery() {
        ops.runLocked("refund-recovery", Duration.ofMinutes(5), () -> recover(stuckAfter));
    }

    public synchronized int recover(Duration olderThan) {
        Instant now = Instant.now();
        List<Refund> stuck = refunds.findByStatusAndUpdatedAtBefore(Refund.PENDING, now.minus(olderThan));
        for (Refund r : stuck) {
            Duration age = Duration.between(r.createdAt, now);
            trace.note("MAS", "Refund recovery job: REFUND_PENDING for " + age.toSeconds() + " s → ask Local Gateway", r.refundId, r.paymentRef);
            Http.Resp resp = bank.lg("refunds/inquiry", Json.obj("refundId", r.refundId));
            if (!resp.ok()) {
                trace.record("MAS", "4:F5", null, "No answer → retry" + (age.compareTo(alertAfter) > 0 ? "; alert ops" : ""), resp.error(), r.paymentRef);
                if (age.compareTo(alertAfter) > 0 && !r.alerted) {
                    r.alerted = true;
                    refunds.save(r);
                    ops.alert("REFUND_STUCK", r.refundId, "REFUND_PENDING for " + age.toSeconds() + " s and Local Gateway does not answer");
                }
                continue;
            }
            String status = Json.str(resp.body(), "status");
            trace.record("MAS", "4:F4", null, "Refund status", status, r.paymentRef);
            if ("REFUNDED".equals(status) || "FAILED".equals(status)) {
                trace.record("MAS", "4:F5", null, status + " → " + status, null, r.paymentRef);
                done(r, status, Json.str(resp.body(), "refundTxnId"));
                continue;
            }
            if (age.compareTo(safeWindow) < 0) {
                trace.record("MAS", "4:F5", null, "NOT_FOUND inside the safe window → keep REFUND_PENDING", null, r.paymentRef);
                continue;
            }
            Http.Resp v = bank.lg("refunds/void", Json.obj("refundId", r.refundId));
            String vs = v.ok() ? Json.str(v.body(), "status") : null;
            if ("VOIDED".equals(vs)) {
                trace.record("MAS", "4:F5b", null, "Void confirmed → FAILED", null, r.paymentRef);
                done(r, "FAILED", null);
            } else if ("POSTED".equals(vs)) {
                done(r, "REFUNDED", Json.str(v.body(), "refundTxnId"));
            }
        }
        return stuck.size();
    }

    private void done(Refund r, String status, String refundTxnId) {
        if (finish(r, status, refundTxnId, "4:F6", "Save final refund status + audit log; FAILED frees the amount left to refund", "4:F7", "4:F8")) {
            trace.record("MAS", "4:F9", null, "Partner reads the final status with Get Refund Status (13–16)", r.refundId, r.paymentRef);
        }
    }
}

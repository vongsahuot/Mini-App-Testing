package com.kosign.mock.portal;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled jobs for payments (section 3, R1–R9b), each on one node at a time:
 * recovery of txns stuck in PROCESSING, and expiry of PENDING / INITIATED txns (releasing any hold).
 */
@Component
public class PaymentJobs {
    private final Payment.Repo payments;
    private final PaymentStore store;
    private final BankClients bank;
    private final Ops ops;
    private final Trace trace;
    private final Duration stuckAfter, safeWindow, alertAfter;

    public PaymentJobs(Payment.Repo payments, PaymentStore store, BankClients bank, Ops ops, Trace trace,
                       @Value("${mock.recovery.stuck-after-seconds}") long stuckAfter,
                       @Value("${mock.recovery.not-found-safe-window-seconds}") long safeWindow,
                       @Value("${mock.recovery.alert-after-seconds}") long alertAfter) {
        this.payments = payments;
        this.store = store;
        this.bank = bank;
        this.ops = ops;
        this.trace = trace;
        this.stuckAfter = Duration.ofSeconds(stuckAfter);
        this.safeWindow = Duration.ofSeconds(safeWindow);
        this.alertAfter = Duration.ofSeconds(alertAfter);
    }

    // ---------- recovery (R1–R6c) ----------

    @Scheduled(fixedDelayString = "${mock.recovery.interval-ms}", initialDelay = 10_000)
    public void scheduledRecovery() {
        ops.runLocked("payment-recovery", Duration.ofMinutes(5), () -> recover(stuckAfter));
    }

    /** Asks Local Gateway about every txn stuck in PROCESSING; never fails one by guessing. */
    public synchronized int recover(Duration olderThan) {
        Instant now = Instant.now();
        List<Payment> stuck = payments.findByStatusAndProcessingAtBefore(Payment.PROCESSING, now.minus(olderThan));
        for (Payment p : stuck) {
            String ref = p.paymentRef;
            Duration age = Duration.between(p.processingAt, now);
            trace.note("MAS", "Recovery job: PROCESSING for " + age.toSeconds() + " s → ask Local Gateway", null, ref);
            Http.Resp r = bank.lg("payments/inquiry", Json.obj("paymentRef", ref));
            if (!r.ok()) {
                trace.record("MAS", "3:R5", null, "No answer → retry next run" + (age.compareTo(alertAfter) > 0 ? "; alert ops" : ""), r.error(), ref);
                if (age.compareTo(alertAfter) > 0 && !p.alerted) {
                    p.alerted = true;
                    payments.save(p);
                    ops.alert("PAYMENT_STUCK", ref, "PROCESSING for " + age.toSeconds() + " s and Local Gateway does not answer");
                }
                continue;
            }
            String status = Json.str(r.body(), "status");
            String txn = Json.str(r.body(), "bankTxnId");
            trace.record("MAS", "3:R4", null, "Payment status", status + (txn == null ? "" : " " + txn), ref);
            switch (status) {
                case "SUCCESS" -> {
                    trace.record("MAS", "3:R5", null, "SUCCESS → SUCCESS", txn, ref);
                    settle(p, Payment.SUCCESS, txn, null);
                }
                case "FAILED" -> {
                    trace.record("MAS", "3:R5", null, "FAILED → FAILED", null, ref);
                    settle(p, Payment.FAILED, null, "DEBIT_FAILED");
                }
                default -> {
                    if (age.compareTo(safeWindow) < 0) {
                        trace.record("MAS", "3:R5", null, "NOT_FOUND inside the safe window → keep PROCESSING", age.toSeconds() + " s < " + safeWindow.toSeconds() + " s", ref);
                        continue;
                    }
                    Http.Resp v = bank.lg("payments/void", Json.obj("paymentRef", ref));
                    String vs = v.ok() ? Json.str(v.body(), "status") : null;
                    if ("VOIDED".equals(vs)) {
                        trace.record("MAS", "3:R5b", null, "Void confirmed → FAILED", null, ref);
                        settle(p, Payment.FAILED, null, "NOT_POSTED");
                    } else if ("POSTED".equals(vs)) {
                        trace.note("MAS", "Void refused: the debit posted meanwhile → SUCCESS", Json.str(v.body(), "bankTxnId"), ref);
                        settle(p, Payment.SUCCESS, Json.str(v.body(), "bankTxnId"), null);
                    } else {
                        trace.record("MAS", "3:R5", null, "Void not confirmed → keep PROCESSING, retry next run", v.error(), ref);
                    }
                }
            }
        }
        return stuck.size();
    }

    private void settle(Payment p, String to, String txn, String reason) {
        if (!store.finish(p.paymentRef, PaymentStore.PROCESSING, to, txn, reason, "3:R6")) return;
        trace.record("MAS", "3:R5c", null, "Save final status + outbox row (one DB transaction)", to, p.paymentRef);
        bank.notifyCustomer(p.customerId, "PAYMENT", Payment.SUCCESS.equals(to) ? "Payment successful" : "Payment failed",
                p.amount + " " + p.currency + " to " + p.merchantName + (Payment.SUCCESS.equals(to) ? " is complete" : " did not go through; no money was taken"),
                p.paymentRef, "3:R6b", "Notify customer (paymentRef, final status)", "3:R6c");
    }

    // ---------- expiry (R7–R9b) ----------

    @Scheduled(fixedDelayString = "${mock.expiry.interval-ms}", initialDelay = 12_000)
    public void scheduledExpiry() {
        ops.runLocked("payment-expiry", Duration.ofMinutes(5), this::expire);
    }

    public synchronized int expire() {
        Instant now = Instant.now();
        int n = 0;
        for (Payment p : payments.findByStatusInAndExpiresAtBefore(PaymentStore.OPEN, now)) {
            String was = p.status;
            if (!store.finish(p.paymentRef, List.of(was), Payment.EXPIRED, null, "EXPIRED", "3:R9b")) continue;
            n++;
            trace.record("MAS", "3:R7", null, "Set EXPIRED only if still PENDING / INITIATED (conditional) + outbox row", "was " + was, p.paymentRef);
            if (Payment.INITIATED.equals(was)) cancelHold(p);
        }
        for (Payment p : payments.findByCancelStatus("PENDING")) cancelHold(p);
        return n;
    }

    private void cancelHold(Payment p) {
        Http.Resp r = bank.lg("payments/cancel", Json.obj("paymentRef", p.paymentRef));
        trace.record("MAS", "3:R9", null, "Cancel response", r.ok() ? Json.str(r.body(), "status") : r.error(), p.paymentRef);
        Payment fresh = payments.findById(p.paymentRef).orElseThrow();
        if (r.ok()) {
            fresh.cancelStatus = "DONE";
        } else {
            boolean first = !"PENDING".equals(fresh.cancelStatus);
            fresh.cancelStatus = "PENDING";
            trace.record("MAS", "3:R9a", null, "Cancel failed → cancelStatus PENDING, retry next run; alert if stuck", r.error(), p.paymentRef);
            if (first) ops.alert("CANCEL_PENDING", p.paymentRef, "Hold release failed for an expired payment; retrying every run");
        }
        payments.save(fresh);
    }

    /** Console: expire every open payment now (to test R7–R9b without waiting). */
    public int expireAllNow() {
        for (Payment p : payments.findAll()) {
            if (PaymentStore.OPEN.contains(p.status)) {
                p.expiresAt = Instant.now().minusSeconds(1);
                payments.save(p);
            }
        }
        return expire();
    }
}

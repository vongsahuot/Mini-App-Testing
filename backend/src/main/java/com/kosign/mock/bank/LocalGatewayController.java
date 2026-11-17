package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Local Gateway (lane LG) in front of Core Banking (lane CB), called by Mini App Service over C8.
 * Every money call is idempotent by paymentRef or refundId. Mini App Service records the replies it receives.
 */
@RestController
@RequestMapping("/lg/v1")
public class LocalGatewayController {
    static final String MAS_CLIENT_ID = "mini-app-service";

    private final Signatures signatures;
    private final Http http;
    private final Ledger ledger;
    private final BankTxn.Repo txns;
    private final Voided.Repo voids;
    private final BankChaos chaos;
    private final Trace trace;
    private final String secret, merchantAccount;
    private final long chaosDelayMs;

    public LocalGatewayController(Signatures signatures, Http http, Ledger ledger, BankTxn.Repo txns, Voided.Repo voids,
                                  BankChaos chaos, Trace trace,
                                  @Value("${mock.bank-secret}") String secret,
                                  @Value("${mock.merchant-account}") String merchantAccount,
                                  @Value("${mock.lg-chaos-delay-ms}") long chaosDelayMs) {
        this.signatures = signatures;
        this.http = http;
        this.ledger = ledger;
        this.txns = txns;
        this.voids = voids;
        this.chaos = chaos;
        this.trace = trace;
        this.secret = secret;
        this.merchantAccount = merchantAccount;
        this.chaosDelayMs = chaosDelayMs;
    }

    @PostMapping("/payments/init")
    public Map<String, Object> init(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String ref = Json.required(b, "paymentRef");
        BigDecimal amount = Json.money(b, "amount");
        trace.record("LG", "3:21", null, "Init payment (hold funds)", Json.str(b, "account") + ", " + amount + " " + Json.str(b, "currency"), ref);
        if (chaos.initFail) {
            trace.note("LG", "Chaos: hold refused", "initFail", ref);
            return Json.obj("status", "REFUSED", "reason", "HOLD_REFUSED");
        }
        String reason = ledger.hold(ref, Json.required(b, "account"), amount, Json.required(b, "currency"));
        if (chaos.initTimeout) {
            trace.note("LG", "Chaos: hold placed, answer delayed past the caller's timeout", "initTimeout", ref);
            sleep();
        }
        return reason == null ? Json.obj("status", "HELD", "reason", null) : Json.obj("status", "REFUSED", "reason", reason);
    }

    @PostMapping("/payments/complete")
    public Map<String, Object> complete(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String ref = Json.required(b, "paymentRef");
        BigDecimal amount = Json.money(b, "amount");
        String currency = Json.required(b, "currency");
        String account = Json.required(b, "account");
        trace.record("LG", "3:35", null, "Complete payment (idempotent by paymentRef)", account + " → " + merchantAccount + ", " + amount, ref);
        if (chaos.completeLost) {
            trace.note("LG", "Chaos: no answer and nothing posted", "completeLost", ref);
            sleep();
            return Json.obj("status", "FAILED", "bankTxnId", null, "reason", "LOST");
        }
        trace.record("CB", "3:36", null, "Debit / credit balance", account + " → " + merchantAccount, ref);
        BankTxn t;
        synchronized (Ledger.LOCK) { // a void and a late complete can never both win
            if (voids.existsById(Voided.payment(ref))) t = ledger.failed(BankTxn.PAYMENT, ref, null, account, merchantAccount, amount, currency, "VOIDED");
            else if (chaos.completeFail) t = ledger.failed(BankTxn.PAYMENT, ref, null, account, merchantAccount, amount, currency, "DEBIT_REFUSED");
            else t = ledger.capture(ref, account, merchantAccount, amount, currency);
        }
        trace.record("LG", "3:37", null, "Debit / credit result", t.status + " " + t.txnId + (t.reason == null ? "" : " " + t.reason), ref);
        if (chaos.completeTimeout) {
            trace.note("LG", "Chaos: posted, answer delayed past the caller's timeout", "completeTimeout", ref);
            sleep();
        }
        return Json.obj("status", t.status, "bankTxnId", BankTxn.SUCCESS.equals(t.status) ? t.txnId : null, "reason", t.reason);
    }

    @PostMapping("/payments/inquiry")
    public Map<String, Object> inquiry(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String ref = Json.required(verify(req, raw), "paymentRef");
        trace.record("LG", "3:R1", null, "Inquiry payment status (paymentRef)", null, ref);
        if (chaos.lgDown) {
            trace.note("LG", "Chaos: Local Gateway does not answer", "lgDown", ref);
            sleep();
        }
        trace.record("CB", "3:R2", null, "Check transaction", null, ref);
        BankTxn t = txns.findFirstByPaymentRefAndTypeAndStatus(ref, BankTxn.PAYMENT, BankTxn.SUCCESS)
                .or(() -> txns.findFirstByPaymentRefAndTypeOrderByCreatedAtDesc(ref, BankTxn.PAYMENT)).orElse(null);
        String status = t == null ? "NOT_FOUND" : t.status;
        trace.record("LG", "3:R3", null, status, t == null ? null : t.txnId, ref);
        return Json.obj("status", status, "bankTxnId", t != null && BankTxn.SUCCESS.equals(t.status) ? t.txnId : null);
    }

    @PostMapping("/payments/void")
    public Map<String, Object> voidComplete(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String ref = Json.required(verify(req, raw), "paymentRef");
        trace.record("LG", "3:R5a", null, "Void complete (paymentRef)", null, ref);
        if (chaos.lgDown) sleep();
        synchronized (Ledger.LOCK) {
            BankTxn posted = txns.findFirstByPaymentRefAndTypeAndStatus(ref, BankTxn.PAYMENT, BankTxn.SUCCESS).orElse(null);
            if (posted != null) return Json.obj("status", "POSTED", "bankTxnId", posted.txnId);
            markVoid(Voided.payment(ref));
        }
        ledger.release(ref);
        return Json.obj("status", "VOIDED", "bankTxnId", null);
    }

    @PostMapping("/payments/cancel")
    public Map<String, Object> cancel(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String ref = Json.required(verify(req, raw), "paymentRef");
        trace.record("LG", "3:R8", null, "Cancel init (release hold), if INITIATED", null, ref);
        if (chaos.cancelFail) {
            trace.note("LG", "Chaos: cancel failed", "cancelFail", ref);
            throw new ApiException(503, "CANCEL_FAILED", "Local Gateway could not cancel the hold");
        }
        return Json.obj("status", ledger.release(ref) ? "CANCELLED" : "NOTHING_HELD");
    }

    @PostMapping("/refunds")
    public Map<String, Object> refund(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String refundId = Json.required(b, "refundId");
        String ref = Json.required(b, "paymentRef");
        BigDecimal amount = Json.money(b, "amount");
        trace.record("LG", "4:4", null, "Refund (refundId, bankTxnId, amount)", refundId + ", " + amount, ref);
        if (chaos.refundLost) {
            trace.note("LG", "Chaos: no answer and nothing posted", "refundLost", ref);
            sleep();
            return Json.obj("status", "FAILED", "refundTxnId", null, "reason", "LOST");
        }
        BankTxn payment = txns.findFirstByPaymentRefAndTypeAndStatus(ref, BankTxn.PAYMENT, BankTxn.SUCCESS).orElse(null);
        String currency = Json.str(b, "currency");
        trace.record("CB", "4:5", null, "Debit merchant / credit customer (1 txn)", merchantAccount + " → "
                + (payment == null ? "?" : payment.debitAccount), ref);
        BankTxn t;
        if (payment == null) t = ledger.failed(BankTxn.REFUND, ref, refundId, merchantAccount, null, amount, currency, "PAYMENT_NOT_FOUND");
        else if (chaos.refundFail) t = ledger.failed(BankTxn.REFUND, ref, refundId, merchantAccount, payment.debitAccount, amount, currency, "REFUND_REFUSED");
        else synchronized (Ledger.LOCK) {
            t = voids.existsById(Voided.refund(refundId))
                    ? ledger.failed(BankTxn.REFUND, ref, refundId, merchantAccount, payment.debitAccount, amount, currency, "VOIDED")
                    : ledger.refund(refundId, ref, merchantAccount, payment.debitAccount, amount, currency);
        }
        trace.record("LG", "4:6", null, "Result", t.status + " " + t.txnId, ref);
        if (chaos.refundTimeout) {
            trace.note("LG", "Chaos: refund posted, answer delayed past the caller's timeout", "refundTimeout", ref);
            sleep();
        }
        return refundView(t);
    }

    @PostMapping("/refunds/inquiry")
    public Map<String, Object> refundInquiry(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String refundId = Json.required(verify(req, raw), "refundId");
        trace.record("LG", "4:F1", null, "Inquiry refund status (refundId)", null, refundId);
        if (chaos.lgDown) sleep();
        trace.record("CB", "4:F2", null, "Check refund transaction", null, refundId);
        BankTxn t = txns.findFirstByRefundIdOrderByCreatedAtDesc(refundId).orElse(null);
        String status = t == null ? "NOT_FOUND" : BankTxn.SUCCESS.equals(t.status) ? "REFUNDED" : "FAILED";
        trace.record("LG", "4:F3", null, status, t == null ? null : t.txnId, refundId);
        return Json.obj("status", status, "refundTxnId", t != null && BankTxn.SUCCESS.equals(t.status) ? t.txnId : null);
    }

    @PostMapping("/refunds/void")
    public Map<String, Object> refundVoid(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String refundId = Json.required(verify(req, raw), "refundId");
        trace.record("LG", "4:F5a", null, "Void refund (refundId)", null, refundId);
        if (chaos.lgDown) sleep();
        synchronized (Ledger.LOCK) {
            BankTxn posted = txns.findFirstByRefundIdOrderByCreatedAtDesc(refundId).filter(t -> BankTxn.SUCCESS.equals(t.status)).orElse(null);
            if (posted != null) return Json.obj("status", "POSTED", "refundTxnId", posted.txnId);
            markVoid(Voided.refund(refundId));
        }
        return Json.obj("status", "VOIDED", "refundTxnId", null);
    }

    private Map<String, Object> refundView(BankTxn t) {
        return Json.obj("status", BankTxn.SUCCESS.equals(t.status) ? "REFUNDED" : "FAILED",
                "refundTxnId", BankTxn.SUCCESS.equals(t.status) ? t.txnId : null, "reason", t.reason);
    }

    private void markVoid(String id) {
        if (voids.existsById(id)) return;
        Voided v = new Voided();
        v.id = id;
        v.at = Instant.now();
        voids.save(v);
    }

    private void sleep() {
        try {
            Thread.sleep(chaosDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode verify(HttpServletRequest req, String raw) {
        signatures.verify(req, raw, id -> MAS_CLIENT_ID.equals(id) ? secret : null);
        return http.parse(raw);
    }
}

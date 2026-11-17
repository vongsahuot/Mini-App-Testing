package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Checksum;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Mini App Service payments (section 3): sealing orders into PENDING txns (verify, API-linkage create order,
 * App-to-App create payment), init and confirm against Local Gateway, and the partner's status inquiry.
 */
@Service
public class PaymentService {
    private final Payment.Repo payments;
    private final MiniApp.Repo apps;
    private final Partner.Repo partners;
    private final ExchangeInfo.Repo exchange;
    private final PaymentStore store;
    private final BankClients bank;
    private final Trace trace;
    private final long ttlMinutes;
    private final String webUrl;

    public PaymentService(Payment.Repo payments, MiniApp.Repo apps, Partner.Repo partners, ExchangeInfo.Repo exchange,
                          PaymentStore store, BankClients bank, Trace trace,
                          @Value("${mock.payment-ttl-minutes}") long ttlMinutes, @Value("${mock.web-url}") String webUrl) {
        this.payments = payments;
        this.apps = apps;
        this.partners = partners;
        this.exchange = exchange;
        this.store = store;
        this.bank = bank;
        this.trace = trace;
        this.ttlMinutes = ttlMinutes;
        this.webUrl = webUrl;
    }

    public static Map<String, Object> info(Payment p) {
        return Json.obj("paymentRef", p.paymentRef, "appId", p.appId, "orderId", p.orderId, "merchantName", p.merchantName,
                "amount", p.amount, "currency", p.currency, "status", p.status, "expiresAt", p.expiresAt);
    }

    public static Map<String, Object> partnerView(Payment p) {
        return Json.obj("paymentRef", p.paymentRef, "orderId", p.orderId, "appId", p.appId, "amount", p.amount, "currency", p.currency,
                "status", p.status, "failReason", p.failReason, "bankTxnId", p.bankTxnId);
    }

    public String payLink(String paymentRef) {
        return webUrl + "/links/mbapp/pay?paymentRef=" + paymentRef;
    }

    // ---------- sealing an order (3:8–3:10, 3:L24–L25, 3:D24–D26) ----------

    /** Webview / SDK: the app passes the sealed order it got from the partner client. */
    public Map<String, Object> verify(Cust c, String appId, JsonNode order, String checksum) {
        MiniApp a = app(appId);
        trace.record("MAS", "3:8", a.type, "Verify transaction", "orderId " + Json.str(order, "orderId"), appId);
        return info(seal(a, order, checksum, c.id(), "3:9", "3:10"));
    }

    /** API linkage: the order came back from the partner's Create Order API. */
    public Payment sealApiOrder(Cust c, MiniApp a, JsonNode order, String checksum) {
        return seal(a, order, checksum, c.id(), "3:L24", "3:L25");
    }

    /** App-to-App: the partner server registers the payment server-to-server before any app switch. */
    public Map<String, Object> createForPartner(Partner caller, String appId, JsonNode order, String checksum) {
        MiniApp a = app(appId);
        trace.record("MAS", "3:D24", "a2a", "Forward API", "orderId " + Json.str(order, "orderId"), appId);
        if (!a.clientId.equals(caller.clientId)) {
            trace.record("MAS", "3:D25", "a2a", "order.appId == caller's appId: no", caller.clientId + " does not own " + appId, appId);
            throw ApiException.forbidden("APP_MISMATCH", "appId " + appId + " does not belong to " + caller.clientId);
        }
        Payment p = seal(a, order, checksum, null, "3:D25", "3:D26");
        return Json.obj("paymentRef", p.paymentRef, "payLink", payLink(p.paymentRef), "expiresAt", p.expiresAt);
    }

    /**
     * Checks the order against the partner's HMAC and the bank's records, then saves (or finds) the PENDING txn.
     * customerId null = resolve the customer from partnerUserId (App-to-App).
     */
    private Payment seal(MiniApp a, JsonNode order, String checksum, String customerId, String checkStep, String saveStep) {
        Partner partner = partners.findById(a.clientId).orElseThrow();
        String orderId = Json.str(order, "orderId");
        String orderApp = Json.str(order, "appId");
        BigDecimal amount = Json.money(order, "amount");
        String currency = Json.str(order, "currency");
        String partnerUserId = Json.str(order, "partnerUserId");
        Instant expiry;
        try {
            expiry = Json.str(order, "expiry") == null ? null : Instant.parse(Json.str(order, "expiry"));
        } catch (DateTimeParseException e) {
            expiry = null;
        }
        ExchangeInfo linked = partnerUserId == null ? null : exchange.findFirstByAppIdAndPartnerUserId(a.appId, partnerUserId).orElse(null);

        ApiException problem = null;
        if (orderId == null || amount == null || currency == null || partnerUserId == null || expiry == null) {
            problem = ApiException.badRequest("INVALID_ORDER", "Order needs appId, orderId, amount, currency, partnerUserId and expiry");
        } else if (!a.appId.equals(orderApp)) {
            problem = ApiException.badRequest("APP_MISMATCH", "order.appId " + orderApp + " is not the Mini App that is open (" + a.appId + ")");
        } else if (checksum == null || !Hmac.equal(Checksum.sign(partner.orderKey, orderApp, orderId, amount, currency, partnerUserId, expiry), checksum)) {
            problem = ApiException.badRequest("BAD_CHECKSUM", "Checksum does not match the order (HMAC with " + partner.name + "'s key)");
        } else if (amount.signum() <= 0 || !"USD".equals(currency)) {
            problem = ApiException.badRequest("INVALID_ORDER", "Amount must be > 0 and currency USD");
        } else if (linked == null) {
            problem = ApiException.badRequest(customerId == null ? "NOT_LINKED" : "WRONG_CUSTOMER", "partnerUserId is not linked to a customer of " + a.appId);
        } else if (customerId != null && !linked.customerId.equals(customerId)) {
            problem = ApiException.badRequest("WRONG_CUSTOMER", "The order was made for another customer");
        } else if (!expiry.isAfter(Instant.now())) {
            problem = ApiException.badRequest("ORDER_EXPIRED", "The order expired at " + expiry);
        }
        String checks = "order.appId == appId; HMAC with that partner's key; amount / currency / partnerUserId / expiry";
        if (problem != null) {
            trace.record("MAS", checkStep, a.type, checks + ": " + problem.code, problem.getMessage(), orderId);
            throw problem;
        }

        Payment existing = payments.findByAppIdAndOrderId(a.appId, orderId).orElse(null);
        if (existing != null) return sameOrder(existing, amount, linked.customerId, a, checkStep);

        Instant now = Instant.now();
        Instant ttl = now.plusSeconds(ttlMinutes * 60);
        Payment p = new Payment();
        p.paymentRef = "pay_" + Hmac.random(10);
        p.appId = a.appId;
        p.clientId = a.clientId;
        p.type = a.type;
        p.orderId = orderId;
        p.customerId = linked.customerId;
        p.partnerUserId = partnerUserId;
        p.amount = amount;
        p.currency = currency;
        p.merchantName = partner.name;
        p.description = Json.str(order, "description");
        p.status = Payment.PENDING;
        p.expiresAt = expiry.isBefore(ttl) ? expiry : ttl;
        p.createdAt = now;
        p.updatedAt = now;
        try {
            payments.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            return sameOrder(payments.findByAppIdAndOrderId(a.appId, orderId).orElseThrow(), amount, linked.customerId, a, checkStep);
        }
        trace.record("MAS", checkStep, a.type, checks + ": ok", "new orderId " + orderId, orderId);
        trace.record("MAS", saveStep, a.type, "Save txn PENDING (bound to customer + appId, expiry = min(order, TTL))",
                p.paymentRef + ", expires " + p.expiresAt, p.paymentRef);
        return p;
    }

    private Payment sameOrder(Payment existing, BigDecimal amount, String customerId, MiniApp a, String checkStep) {
        if (Payment.PENDING.equals(existing.status) && existing.amount.compareTo(amount) == 0 && existing.customerId.equals(customerId)) {
            trace.record("MAS", checkStep, a.type, "Same PENDING order → same paymentRef", existing.paymentRef, existing.orderId);
            return existing;
        }
        trace.record("MAS", checkStep, a.type, "orderId already used by a " + existing.status + " txn", existing.paymentRef, existing.orderId);
        throw ApiException.conflict("ORDER_USED", "orderId " + existing.orderId + " was already used (" + existing.status + ")");
    }

    // ---------- reading a txn (3:17a, 3:D34–D35, 3:45c) ----------

    public Map<String, Object> get(Cust c, String paymentRef, String purpose) {
        Payment p = payments.findById(paymentRef).orElse(null);
        switch (purpose) {
            case "pay-link" -> {
                trace.record("MAS", "3:D34", "a2a", "Get txn (paymentRef, customer)", null, paymentRef);
                ApiException problem = p == null ? ApiException.notFound("No payment " + paymentRef)
                        : !p.customerId.equals(c.id()) ? ApiException.forbidden("WRONG_CUSTOMER", "This payment belongs to another customer")
                        : Payment.EXPIRED.equals(p.status) || !p.expiresAt.isAfter(Instant.now()) ? ApiException.conflict("EXPIRED", "This payment has expired")
                        : !Payment.PENDING.equals(p.status) ? ApiException.conflict("NOT_PENDING", "This payment is " + p.status) : null;
                trace.record("MAS", "3:D35", "a2a", "Txn PENDING, not expired, and bound to this signed-in customer",
                        problem == null ? "ok" : problem.code, paymentRef);
                if (problem != null) throw problem;
                return info(p);
            }
            case "status" -> {
                trace.record("MAS", "3:45c", null, "Get payment status", p == null ? "not found" : p.status, paymentRef);
                Payment own = owned(p, c, paymentRef);
                return Json.obj("paymentRef", own.paymentRef, "status", own.status, "failReason", own.failReason, "bankTxnId", own.bankTxnId,
                        "amount", own.amount, "currency", own.currency, "merchantName", own.merchantName, "appId", own.appId);
            }
            default -> {
                trace.record("MAS", "3:17a", null, "Get txn (paymentRef, customer)", null, paymentRef);
                return info(owned(p, c, paymentRef));
            }
        }
    }

    private static Payment owned(Payment p, Cust c, String paymentRef) {
        if (p == null) throw ApiException.notFound("No payment " + paymentRef);
        if (!p.customerId.equals(c.id())) throw ApiException.forbidden("WRONG_CUSTOMER", "This payment belongs to another customer");
        return p;
    }

    // ---------- 3:19–3:23, 3:22a init ----------

    public Map<String, Object> init(Cust c, String paymentRef, String account) {
        trace.record("MAS", "3:19", null, "Request payment", "account " + account, paymentRef);
        Payment p = owned(payments.findById(paymentRef).orElse(null), c, paymentRef);
        ApiException problem = !Payment.PENDING.equals(p.status) ? ApiException.conflict("NOT_PENDING", "Payment is " + p.status)
                : !p.expiresAt.isAfter(Instant.now()) ? ApiException.conflict("EXPIRED", "Payment has expired") : null;
        trace.record("MAS", "3:20", null, "Validate paymentRef, PENDING, amount, expiry", problem == null ? "ok" : problem.code, paymentRef);
        if (problem != null) throw problem;

        Http.Resp r = bank.lg("payments/init", Json.obj("paymentRef", paymentRef, "account", account, "amount", p.amount, "currency", p.currency));
        String status = r.ok() ? Json.str(r.body(), "status") : null;
        trace.record("MAS", "3:22", null, "Init response", r.ok() ? status + (Json.str(r.body(), "reason") == null ? "" : " " + Json.str(r.body(), "reason"))
                : r.timedOut() ? "no answer (timeout)" : r.error(), paymentRef);
        if ("HELD".equals(status) && store.initiate(paymentRef, account)) {
            trace.record("MAS", "3:23", null, "Update txn INITIATED", null, paymentRef);
            return info(payments.findById(paymentRef).orElseThrow());
        }
        if (r.timedOut()) {
            // The hold may exist: mark INITIATED so the expiry job releases it (R8).
            store.initiate(paymentRef, account);
            trace.record("MAS", "3:22a", null, "Timeout → set INITIATED (R8 cancels hold at expiry)", null, paymentRef);
            throw new ApiException(504, "INIT_TIMEOUT", "The bank did not answer in time; the payment will be cancelled");
        }
        String reason = r.ok() ? Json.str(r.body(), "reason") : r.error();
        trace.record("MAS", "3:22a", null, "Failed → keep PENDING", reason, paymentRef);
        throw new ApiException(502, "INIT_FAILED", "The bank could not hold the funds (" + reason + ")", Map.of("reason", String.valueOf(reason)));
    }

    // ---------- 3:33–3:39, 3:38a, 3:38g confirm ----------

    public Map<String, Object> confirm(Cust c, String paymentRef) {
        trace.record("MAS", "3:33", null, "Confirm payment", null, paymentRef);
        Payment p = owned(payments.findById(paymentRef).orElse(null), c, paymentRef);
        if (!store.process(paymentRef)) {
            p = payments.findById(paymentRef).orElseThrow();
            trace.record("MAS", "3:34", null, "Set PROCESSING only if INITIATED and not expired: 0 rows", "status " + p.status, paymentRef);
            if (p.isFinal() || Payment.PROCESSING.equals(p.status)) return result(p); // repeated confirm: same answer, no second debit
            throw ApiException.conflict(p.expiresAt.isAfter(Instant.now()) ? "NOT_INITIATED" : "EXPIRED", "Payment is " + p.status);
        }
        trace.record("MAS", "3:34", null, "Set PROCESSING only if INITIATED and not expired (1 row updated)", null, paymentRef);
        Partner partner = partners.findById(p.clientId).orElseThrow();
        Http.Resp r = bank.lg("payments/complete", Json.obj("paymentRef", paymentRef, "account", p.account,
                "merchantAccount", partner.merchantAccount, "amount", p.amount, "currency", p.currency));
        String status = r.ok() ? Json.str(r.body(), "status") : null;
        trace.record("MAS", "3:38", null, "Complete response", r.ok() ? status : r.timedOut() ? "no answer (timeout)" : r.error(), paymentRef);
        if ("SUCCESS".equals(status)) {
            store.finish(paymentRef, PaymentStore.PROCESSING, Payment.SUCCESS, Json.str(r.body(), "bankTxnId"), null, "3:40");
            trace.record("MAS", "3:39", null, "Update txn SUCCESS + outbox row (one DB transaction)", Json.str(r.body(), "bankTxnId"), paymentRef);
        } else if ("FAILED".equals(status)) {
            String reason = Json.str(r.body(), "reason");
            store.finish(paymentRef, PaymentStore.PROCESSING, Payment.FAILED, null, reason, "3:38b");
            trace.record("MAS", "3:38a", null, "Update txn FAILED (failReason) + outbox row (one DB transaction)", reason, paymentRef);
        } else {
            trace.record("MAS", "3:38g", null, "Keep txn PROCESSING; recovery job resolves it (R1–R6c)", r.error(), paymentRef);
        }
        return result(payments.findById(paymentRef).orElseThrow());
    }

    private static Map<String, Object> result(Payment p) {
        Map<String, Object> m = info(p);
        m.put("bankTxnId", p.bankTxnId);
        m.put("failReason", p.failReason);
        return m;
    }

    // ---------- partner status inquiry (47b–47ba, L32–L33, D41b–D41c) ----------

    public Payment inquiry(Partner caller, String paymentRef, String orderId) {
        Payment p = paymentRef != null ? payments.findById(paymentRef).orElse(null)
                : orderId == null ? null : payments.findByClientIdAndOrderId(caller.clientId, orderId).stream().findFirst().orElse(null);
        String type = p == null ? null : p.type;
        String ref = paymentRef != null ? paymentRef : orderId;
        trace.record("MAS", key(type, "3:47b", "3:L32", "3:D41b"), type, "Forward API (partner gateway: IP allowlist + mTLS, rate limit)", null, ref);
        boolean owned = p != null && p.clientId.equals(caller.clientId);
        trace.record("MAS", key(type, "3:47ba", "3:L33", "3:D41c"), type, "Check paymentRef / orderId belongs to the caller's appId",
                owned ? "ok, " + p.status : "not found for " + caller.clientId, ref);
        if (!owned) throw ApiException.notFound("No payment " + ref + " for " + caller.clientId);
        return p;
    }

    /** Step key of the right diagram: webview / sdk, API linkage, App-to-App. */
    static String key(String type, String webSdk, String api, String a2a) {
        return MiniApp.A2A.equals(type) ? a2a : MiniApp.API.equals(type) ? api : webSdk;
    }

    private MiniApp app(String appId) {
        MiniApp a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Mini App " + appId + " not found"));
        if (!a.enabled) throw ApiException.conflict("APP_DISABLED", a.name + " is not available right now");
        return a;
    }

    static final List<String> FINAL = List.of(Payment.SUCCESS, Payment.FAILED, Payment.EXPIRED);
}

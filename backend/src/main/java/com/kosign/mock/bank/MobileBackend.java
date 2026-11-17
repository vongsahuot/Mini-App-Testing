package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mobile Backend (lane MBE). Receives calls the app gateway has authenticated, forwards Mini App calls to
 * Mini App Service with the customer taken from the session (C3), and owns the bank-side payment checks:
 * account ownership, balance, limit, auth method, PIN / OTP and push notifications.
 */
@Service
public class MobileBackend {
    private final MasClient mas;
    private final Account.Repo accounts;
    private final Customer.Repo customers;
    private final PaymentAuth.Repo auths;
    private final Notification.Repo notifications;
    private final Trace trace;
    private final BigDecimal limit, otpThreshold;
    private final int maxAttempts;
    /** appId → integration type, learned from Mini App Service answers; only used to label trace steps. */
    private final Map<String, String> appTypes = new ConcurrentHashMap<>();

    public MobileBackend(MasClient mas, Account.Repo accounts, Customer.Repo customers, PaymentAuth.Repo auths,
                         Notification.Repo notifications, Trace trace,
                         @Value("${mock.payment-limit}") BigDecimal limit,
                         @Value("${mock.otp-threshold}") BigDecimal otpThreshold,
                         @Value("${mock.max-auth-attempts}") int maxAttempts) {
        this.mas = mas;
        this.accounts = accounts;
        this.customers = customers;
        this.auths = auths;
        this.notifications = notifications;
        this.trace = trace;
        this.limit = limit;
        this.otpThreshold = otpThreshold;
        this.maxAttempts = maxAttempts;
    }

    public String typeOf(String appId) {
        return appId == null ? null : appTypes.get(appId);
    }

    /** Picks the step key of the right diagram for this Mini App's type (webview/sdk, api, a2a). */
    public String key(String appId, String webSdk, String api, String a2a) {
        String t = typeOf(appId);
        return "a2a".equals(t) && a2a != null ? a2a : "api".equals(t) && api != null ? api : webSdk;
    }

    // ---------- Section 1–2: Mini Apps ----------

    public JsonNode list(Customer c, String appVersion, String platform, String etag) {
        trace.record("MBE", "1:3", null, "Forward API", "List Mini Apps for " + c.customerId, null);
        JsonNode r = forward(c, "mini-apps/list", Json.obj("appVersion", appVersion, "platform", platform, "etag", etag),
                "1:6", "Mini App list", null, null, null);
        r.path("sections").forEach(s -> s.path("apps").forEach(a -> appTypes.put(a.path("appId").asText(), a.path("type").asText())));
        return r;
    }

    public JsonNode detail(Customer c, String appId) {
        trace.record("MBE", "2:3", typeOf(appId), "Forward API", "Get Mini App Detail", appId);
        Http.Resp r = mas.call("mini-apps/detail", c, Json.obj("appId", appId));
        if (r.ok()) appTypes.put(appId, Json.str(r.body(), "type"));
        return answer(r, "2:6", "Detail", "2:6", typeOf(appId), appId);
    }

    public JsonNode consent(Customer c, String appId, String version) {
        String flow = typeOf(appId);
        trace.record("MBE", "2:12", flow, "Forward API", "Accept Consent v" + version, appId);
        return answer(mas.call("mini-apps/consent", c, Json.obj("appId", appId, "consentVersion", version)),
                "2:21", "OK (consent ACCEPTED)", "2:19b", flow, appId);
    }

    public JsonNode authCode(Customer c, String appId, String codeChallenge, String method) {
        String flow = typeOf(appId);
        trace.record("MBE", key(appId, "2:29", null, "2:A8"), flow, "Forward API", "Get Auth Code", appId);
        return answer(mas.call("mini-apps/auth-code", c, Json.obj("appId", appId, "codeChallenge", codeChallenge, "codeChallengeMethod", method)),
                key(appId, "2:32", null, "2:A11"), "authCode", null, flow, appId);
    }

    public JsonNode content(Customer c, String appId, String operation, JsonNode params) {
        trace.record("MBE", "2:L3", "api", "Forward API (app gateway: session token, rate limit)", operation, appId);
        return answer(mas.call("mini-apps/content", c, Json.obj("appId", appId, "operation", operation, "params", params)),
                "2:L11", "Content", "2:L8b", "api", appId);
    }

    public JsonNode createOrder(Customer c, String appId, JsonNode items, String idempotencyKey) {
        trace.record("MBE", "3:L17", "api", "Forward API (app gateway: session token, rate limit)", idempotencyKey, appId);
        return answer(mas.call("mini-apps/orders", c, Json.obj("appId", appId, "items", items, "idempotencyKey", idempotencyKey)),
                "3:L26", "Payment info (paymentRef, merchant from registry, amount, currency)", "3:L24b", "api", appId);
    }

    // ---------- Section 3: payment ----------

    public JsonNode verify(Customer c, String appId, JsonNode order, String checksum) {
        String flow = typeOf(appId);
        trace.record("MBE", "3:7", flow, "Forward API (app gateway: session token, rate limit)", "orderId " + Json.str(order, "orderId"), appId);
        return answer(mas.call("payments/verify", c, Json.obj("appId", appId, "order", order, "checksum", checksum)),
                "3:11", "Payment info (paymentRef, merchant from registry, amount, currency)", "3:55", flow, Json.str(order, "orderId"));
    }

    /** Get Payment Info for a payLink (App-to-App, D33–D36). */
    public JsonNode paymentInfo(Customer c, String paymentRef) {
        trace.record("MBE", "3:D33", "a2a", "Forward API (app gateway: session token, rate limit)", null, paymentRef);
        return answer(mas.call("payments/get", c, Json.obj("paymentRef", paymentRef, "purpose", "pay-link")),
                "3:D36", "Payment info (paymentRef, merchant from registry, amount, currency)", "3:D35a", "a2a", paymentRef);
    }

    public JsonNode status(Customer c, String paymentRef) {
        trace.record("MBE", "3:45b", null, "Forward API", null, paymentRef);
        return answer(mas.call("payments/get", c, Json.obj("paymentRef", paymentRef, "purpose", "status")), "3:45d", "Status", null, null, paymentRef);
    }

    public Map<String, Object> init(Customer c, String paymentRef, String accountNo) {
        trace.record("MBE", "3:17", null, "Forward API", "account " + accountNo, paymentRef);
        Http.Resp got = mas.call("payments/get", c, Json.obj("paymentRef", paymentRef, "purpose", "init"));
        if (!got.ok()) {
            trace.note("MBE", "Get txn failed: " + got.error(), Json.str(got.body(), "message"), paymentRef);
            throw ApiException.from(got);
        }
        JsonNode txn = got.body();
        BigDecimal amount = Json.money(txn, "amount");
        String currency = Json.str(txn, "currency");
        trace.record("MBE", "3:17b", null, "Amount, currency, status", amount + " " + currency + ", " + Json.str(txn, "status"), paymentRef);

        Account a = accounts.findById(accountNo).orElse(null);
        String problem = a == null || !c.customerId.equals(a.customerId) ? "ACCOUNT_NOT_OWNED"
                : !a.currency.equals(currency) ? "CURRENCY_MISMATCH"
                : amount.compareTo(limit) > 0 ? "LIMIT_EXCEEDED"
                : a.available().compareTo(amount) < 0 ? "INSUFFICIENT_FUNDS" : null;
        trace.record("MBE", "3:18", null, "Validate account ownership, currency, balance, limit (server amount)",
                problem == null ? "ok: available " + a.available() : problem, paymentRef);
        if (problem != null) throw ApiException.unprocessable(problem, "Payment stopped before init: " + problem);

        JsonNode r = answer(mas.call("payments/init", c, Json.obj("paymentRef", paymentRef, "account", accountNo)),
                "3:24", "Init success", "3:22b", null, paymentRef);

        String method = amount.compareTo(otpThreshold) >= 0 ? PaymentAuth.OTP : PaymentAuth.PIN;
        PaymentAuth pa = new PaymentAuth();
        pa.paymentRef = paymentRef;
        pa.customerId = c.customerId;
        pa.method = method;
        pa.amount = amount;
        pa.currency = currency;
        pa.account = accountNo;
        pa.expiresAt = Instant.now().plusSeconds(300);
        if (PaymentAuth.OTP.equals(method)) pa.otp = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        auths.save(pa);
        trace.record("MBE", "3:25", null, "Get auth method; if OTP, send OTP bound to paymentRef + amount", method, paymentRef);
        if (pa.otp != null) {
            push(c.customerId, "OTP", "Your OTP is " + pa.otp,
                    "OTP " + pa.otp + " for " + amount + " " + currency + " to " + Json.str(r, "merchantName") + ". Valid 5 minutes.", paymentRef, null);
        }
        return Json.obj("paymentRef", paymentRef, "status", Json.str(r, "status"), "amount", amount, "currency", currency,
                "merchantName", Json.str(r, "merchantName"), "authMethod", method);
    }

    /** Step 3:32: validates PIN / OTP. Returns null when ok, or the failure (with attempts left / locked). */
    public synchronized ApiException checkAuth(Customer c, String paymentRef, String pin, String otp) {
        Customer fresh = customers.findById(c.customerId).orElseThrow();
        if (fresh.authLocked) return new ApiException(423, "AUTH_LOCKED", "Payment authentication is locked after " + maxAttempts + " wrong tries");
        PaymentAuth pa = auths.findById(paymentRef).filter(x -> x.customerId.equals(c.customerId)).orElse(null);
        if (pa == null) return ApiException.conflict("NOT_INITIATED", "Init the payment first");
        boolean ok = PaymentAuth.OTP.equals(pa.method)
                ? otp != null && Hmac.equal(otp, pa.otp) && pa.expiresAt.isAfter(Instant.now())
                : pin != null && Hmac.equal(pin, fresh.pin);
        if (ok) {
            fresh.authFailures = 0;
            customers.save(fresh);
            return null;
        }
        fresh.authFailures++;
        if (fresh.authFailures >= maxAttempts) fresh.authLocked = true;
        customers.save(fresh);
        int left = Math.max(0, maxAttempts - fresh.authFailures);
        return fresh.authLocked
                ? new ApiException(423, "AUTH_LOCKED", "Payment authentication is locked after " + maxAttempts + " wrong tries", Map.of("attemptsLeft", 0))
                : new ApiException(401, "AUTH_FAILED", "Wrong " + pa.method + ", " + left + " attempt(s) left", Map.of("attemptsLeft", left));
    }

    public JsonNode confirm(Customer c, String paymentRef) {
        Http.Resp r = mas.call("payments/confirm", c, Json.obj("paymentRef", paymentRef));
        if (!r.ok()) {
            trace.note("MBE", "Confirm failed: " + r.error(), Json.str(r.body(), "message"), paymentRef);
            throw ApiException.from(r);
        }
        JsonNode b = r.body();
        String status = Json.str(b, "status");
        switch (status) {
            case "SUCCESS" -> {
                trace.record("MBE", "3:41", null, "Payment result", "SUCCESS " + Json.str(b, "bankTxnId"), paymentRef);
                push(c.customerId, "PAYMENT", "Payment successful", Json.money(b, "amount") + " " + Json.str(b, "currency")
                        + " paid to " + Json.str(b, "merchantName"), paymentRef, "3:42");
            }
            case "FAILED" -> trace.record("MBE", "3:38c", null, "Payment result (FAILED, errorCode)", Json.str(b, "failReason"), paymentRef);
            default -> trace.record("MBE", "3:38h", null, "Payment result (PROCESSING)", null, paymentRef);
        }
        return b;
    }

    // ---------- push (C10) ----------

    public void push(String customerId, String kind, String title, String body, String ref, String step) {
        Notification n = new Notification();
        n.customerId = customerId;
        n.kind = kind;
        n.title = title;
        n.body = body;
        n.ref = ref;
        n.createdAt = Instant.now();
        notifications.save(n);
        if (step != null) trace.record("U", step, null, "Push notification", title + ": " + body, ref);
    }

    // ---------- helpers ----------

    private JsonNode forward(Customer c, String path, Map<String, Object> body, String okStep, String okTitle, String errStep, String flow, String ref) {
        return answer(mas.call(path, c, body), okStep, okTitle, errStep, flow, ref);
    }

    /** Records the MBE step for Mini App Service's answer and passes it on (or re-throws its error). */
    private JsonNode answer(Http.Resp r, String okStep, String okTitle, String errStep, String flow, String ref) {
        if (r.ok()) {
            trace.record("MBE", okStep, flow, okTitle, summary(r.body()), ref);
            return r.body();
        }
        String title = "Error (" + r.error() + ")";
        if (errStep != null) trace.record("MBE", errStep, flow, title, Json.str(r.body(), "message"), ref);
        else trace.note("MBE", title, Json.str(r.body(), "message"), ref);
        throw ApiException.from(r);
    }

    private static String summary(JsonNode b) {
        for (String f : new String[]{"paymentRef", "status", "authCode", "etag"}) {
            String v = Json.str(b, f);
            if (v != null) return f + "=" + v;
        }
        return null;
    }
}

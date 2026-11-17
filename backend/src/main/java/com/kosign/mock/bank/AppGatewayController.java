package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.RateLimiter;
import com.kosign.mock.common.Trace;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Public Gateway, app side (lane PG, C1): checks the customer session token and the rate limit, then forwards
 * to Mobile Backend (C2, in-process here). The customer always comes from the session, never from the body.
 */
@RestController
@RequestMapping("/gw/app/v1")
public class AppGatewayController {
    private final Customer.Repo customers;
    private final Account.Repo accounts;
    private final BankSession.Repo sessions;
    private final Notification.Repo notifications;
    private final MobileBackend mbe;
    private final RateLimiter rateLimiter;
    private final Trace trace;

    public AppGatewayController(Customer.Repo customers, Account.Repo accounts, BankSession.Repo sessions,
                                Notification.Repo notifications, MobileBackend mbe, RateLimiter rateLimiter, Trace trace) {
        this.customers = customers;
        this.accounts = accounts;
        this.sessions = sessions;
        this.notifications = notifications;
        this.mbe = mbe;
        this.rateLimiter = rateLimiter;
        this.trace = trace;
    }

    // ---------- session ----------

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        Customer c = customers.findByUsername(Json.required(body, "username").trim())
                .filter(x -> x.pin.equals(Json.str(body, "pin")))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Wrong username or PIN"));
        BankSession s = new BankSession();
        s.token = "sess_" + Hmac.random(16);
        s.customerId = c.customerId;
        s.deviceId = Json.str(body, "deviceId");
        s.createdAt = Instant.now();
        sessions.save(s);
        return Json.obj("sessionToken", s.token, "customerId", c.customerId, "name", c.name, "phone", c.phone,
                "segment", c.segment, "accounts", accountViews(c.customerId));
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts(@RequestHeader(value = "Authorization", required = false) String auth) {
        return accountViews(customer(auth).customerId);
    }

    @GetMapping("/notifications")
    public List<Notification> notifications(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @RequestParam(defaultValue = "0") long after) {
        return notifications.findByCustomerIdAndIdGreaterThanOrderById(customer(auth).customerId, after);
    }

    // ---------- Section 1–2: Mini Apps ----------

    @GetMapping("/mini-apps")
    public ResponseEntity<Object> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestHeader(value = "If-None-Match", required = false) String etag,
                                       @RequestHeader(value = "X-App-Version", required = false) String appVersion,
                                       @RequestHeader(value = "X-Platform", required = false) String platform) {
        Customer c = enter(auth, "1:2", null, "List Mini App API", "app " + appVersion + " · " + platform, null);
        JsonNode r = mbe.list(c, appVersion == null ? "0.0.0" : appVersion, platform, etag);
        String tag = Json.str(r, "etag");
        boolean notModified = r.path("notModified").asBoolean(false);
        trace.record("PG", "1:7", null, "Mini App list", notModified ? "304 Not Modified" : "200, etag " + tag, null);
        return notModified
                ? ResponseEntity.status(304).eTag(tag).build()
                : ResponseEntity.ok().eTag(tag).body(Json.obj("sections", r.get("sections"), "etag", tag));
    }

    @GetMapping("/mini-apps/{appId}")
    public JsonNode detail(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String appId) {
        Customer c = enter(auth, "2:2", mbe.typeOf(appId), "Get Mini App Detail (appId)", null, appId);
        return pass(() -> mbe.detail(c, appId), "2:7", "Detail", "2:7", appId);
    }

    @PostMapping("/mini-apps/{appId}/consent")
    public JsonNode consent(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String appId,
                            @RequestBody JsonNode body) {
        String version = Json.required(body, "consentVersion");
        Customer c = enter(auth, "2:11", mbe.typeOf(appId), "Accept Consent API (appId, consentVersion)", "v" + version, appId);
        return pass(() -> mbe.consent(c, appId, version), "2:22", "OK (consent ACCEPTED)", "2:19c", appId);
    }

    @PostMapping("/mini-apps/{appId}/auth-code")
    public JsonNode authCode(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String appId,
                             @RequestBody(required = false) JsonNode body) {
        String challenge = Json.str(body, "codeChallenge");
        Customer c = enter(auth, mbe.key(appId, "2:28", null, "2:A7"), mbe.typeOf(appId),
                challenge == null ? "Get Auth Code API (appId)" : "Get Auth Code API (appId, codeChallenge)", null, appId);
        return pass(() -> mbe.authCode(c, appId, challenge, Json.str(body, "codeChallengeMethod")),
                mbe.key(appId, "2:33", null, "2:A12"), "authCode", null, appId);
    }

    @PostMapping("/mini-apps/{appId}/content")
    public JsonNode content(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String appId,
                            @RequestBody JsonNode body) {
        String op = Json.required(body, "operation");
        Customer c = enter(auth, "2:L2", "api", "Partner Content API (appId, operation, params)", op, appId);
        return pass(() -> mbe.content(c, appId, op, body.path("params")), "2:L12", "Content", "2:L8c", appId);
    }

    @PostMapping("/mini-apps/{appId}/orders")
    public JsonNode createOrder(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String appId,
                                @RequestBody JsonNode body) {
        String key = Json.required(body, "idempotencyKey");
        Customer c = enter(auth, "3:L16", "api", "Create Order API (appId, items, idempotencyKey)", key, appId);
        return pass(() -> mbe.createOrder(c, appId, body.path("items"), key), "3:L27", "Payment info", "3:L24c", appId);
    }

    // ---------- Section 3: payment ----------

    @PostMapping("/payments/verify")
    public JsonNode verify(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode body) {
        String appId = Json.required(body, "appId");
        Customer c = enter(auth, "3:6", mbe.typeOf(appId), "Verify Transaction API (appId from MB App, order, checksum)",
                "orderId " + Json.str(body.path("order"), "orderId"), appId);
        return pass(() -> mbe.verify(c, appId, body.path("order"), Json.str(body, "checksum")), "3:12", "Payment info", "3:56", appId);
    }

    @GetMapping("/payments/{paymentRef}")
    public JsonNode paymentInfo(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String paymentRef) {
        Customer c = enter(auth, "3:D32", "a2a", "Get Payment Info API (paymentRef)", null, paymentRef);
        return pass(() -> mbe.paymentInfo(c, paymentRef), "3:D37", "Payment info", "3:D35b", paymentRef);
    }

    @GetMapping("/payments/{paymentRef}/status")
    public JsonNode status(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String paymentRef) {
        Customer c = enter(auth, "3:45a", null, "Get Payment Status API (paymentRef)", null, paymentRef);
        return pass(() -> mbe.status(c, paymentRef), "3:45e", "Status", null, paymentRef);
    }

    @PostMapping("/payments/{paymentRef}/init")
    public Map<String, Object> init(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String paymentRef,
                                    @RequestBody JsonNode body) {
        String account = Json.required(body, "account");
        Customer c = enter(auth, "3:16", null, "Init Payment API (paymentRef, account)", account, paymentRef);
        try {
            Map<String, Object> r = mbe.init(c, paymentRef, account);
            trace.record("PG", "3:26", null, "Txn info, auth method (PIN/OTP)", String.valueOf(r.get("authMethod")), paymentRef);
            return r;
        } catch (ApiException e) {
            trace.record("PG", "3:22c", null, "Failed", e.code, paymentRef);
            throw e;
        }
    }

    @PostMapping("/payments/{paymentRef}/confirm")
    public JsonNode confirm(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String paymentRef,
                            @RequestBody JsonNode body) {
        Customer c = enter(auth, "3:30", null, "Confirm Payment API (paymentRef, PIN/OTP)", body.has("otp") ? "OTP" : "PIN", paymentRef);
        trace.record("MBE", "3:31", null, "Forward API", null, paymentRef);
        ApiException authError = mbe.checkAuth(c, paymentRef, Json.str(body, "pin"), Json.str(body, "otp"));
        trace.record("MBE", "3:32", null, "Validate request & authentication", authError == null ? "ok" : authError.code, paymentRef);
        if (authError != null) {
            trace.record("PG", "3:32a", null, "Auth failed (attempts left / locked)", authError.getMessage(), paymentRef);
            throw authError;
        }
        JsonNode r = mbe.confirm(c, paymentRef);
        String status = Json.str(r, "status");
        switch (status) {
            case "SUCCESS" -> trace.record("PG", "3:43", null, "Success", Json.str(r, "bankTxnId"), paymentRef);
            case "FAILED" -> trace.record("PG", "3:38d", null, "Failed", Json.str(r, "failReason"), paymentRef);
            default -> trace.record("PG", "3:38i", null, "Processing", null, paymentRef);
        }
        return r;
    }

    // ---------- helpers ----------

    /** Session check + rate limit (C1), then records the gateway's receipt of the call. */
    private Customer enter(String auth, String step, String flow, String title, String detail, String ref) {
        Customer c = customer(auth);
        rateLimiter.check("app:" + c.customerId);
        trace.record("PG", step, flow, title, detail, ref);
        return c;
    }

    /** Records the gateway's receipt of Mobile Backend's answer (or error) on its way back to the app. */
    private JsonNode pass(Supplier<JsonNode> call, String okStep, String okTitle, String errStep, String ref) {
        String flow = mbe.typeOf(ref);
        try {
            JsonNode r = call.get();
            trace.record("PG", okStep, flow == null ? mbe.typeOf(Json.str(r, "appId")) : flow, okTitle, null, ref);
            return r;
        } catch (ApiException e) {
            if (errStep != null) trace.record("PG", errStep, flow, "Error", e.code, ref);
            throw e;
        }
    }

    private Customer customer(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw ApiException.unauthorized("MISSING_TOKEN", "Customer session token required");
        BankSession s = sessions.findById(auth.substring(7).trim())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_SESSION", "Session expired, please log in again"));
        return customers.findById(s.customerId).orElseThrow();
    }

    private List<Map<String, Object>> accountViews(String customerId) {
        return accounts.findByCustomerIdOrderByAccountNo(customerId).stream()
                .map(a -> Json.obj("accountNo", a.accountNo, "name", a.name, "balance", a.balance, "held", a.held,
                        "available", a.available(), "currency", a.currency))
                .toList();
    }
}

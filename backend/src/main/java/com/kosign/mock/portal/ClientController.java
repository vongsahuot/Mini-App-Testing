package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bank app → KOSIGN, authenticated with the bank's short-lived JWT. */
@RestController
@RequestMapping("/api/v1/client")
public class ClientController {
    private static final Map<String, String> SCOPE_LABELS = Map.of("profile", "Your name", "phone", "Your phone number");
    private static final int CACHE_TTL_SECONDS = 60;

    private final JwtVerifier jwt;
    private final MiniApp.Repo apps;
    private final Partner.Repo partners;
    private final AppSession.Repo sessions;
    private final Consent.Repo consents;
    private final AuthCode.Repo authCodes;
    private final Payment.Repo payments;
    private final Trace trace;
    private final long authCodeTtl;

    public ClientController(JwtVerifier jwt, MiniApp.Repo apps, Partner.Repo partners, AppSession.Repo sessions,
                            Consent.Repo consents, AuthCode.Repo authCodes, Payment.Repo payments, Trace trace,
                            @Value("${mock.auth-code-ttl-seconds}") long authCodeTtl) {
        this.jwt = jwt;
        this.apps = apps;
        this.partners = partners;
        this.sessions = sessions;
        this.consents = consents;
        this.authCodes = authCodes;
        this.payments = payments;
        this.trace = trace;
        this.authCodeTtl = authCodeTtl;
    }

    @GetMapping("/mini-apps")
    public Map<String, Object> miniApps(@RequestHeader(value = "Authorization", required = false) String auth) {
        trace.record(2, "GET /client/mini-apps (Bearer JWT, aud = KOSIGN)", null, null);
        JwtVerifier.Customer c = customer(auth, null);

        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        List<MiniApp> all = apps.findAllByOrderBySortOrder();
        for (MiniApp a : all) {
            Map<String, Object> s = sections.computeIfAbsent(a.sectionId,
                    id -> Json.obj("id", id, "title", a.sectionTitle, "apps", new ArrayList<Map<String, Object>>()));
            @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) s.get("apps");
            list.add(Json.obj("appId", a.appId, "name", a.name, "icon", a.icon, "badge", a.badge, "enabled", a.enabled));
        }
        trace.record(3, "Verify JWT via bank JWKS: signature, exp, iss, aud; filter apps",
                "sub=" + c.id() + ", " + all.size() + " apps", null);

        List<Map<String, Object>> banners = List.of(
                Json.obj("id", "b1", "title", "Movie night, sorted", "subtitle", "Book tickets without leaving the bank app",
                        "appId", "movie-tickets", "color", "#6A4FD0"),
                Json.obj("id", "b2", "title", "Coffee is coming", "subtitle", "Order ahead, pay with one tap",
                        "appId", "coffee", "color", "#B5650C"));
        return Json.obj("sections", new ArrayList<>(sections.values()), "banners", banners, "cacheTtlSeconds", CACHE_TTL_SECONDS);
    }

    @PostMapping("/mini-apps/{appId}/sessions")
    public Map<String, Object> openSession(@PathVariable String appId,
                                           @RequestHeader(value = "Authorization", required = false) String auth) {
        trace.record(7, "POST /client/mini-apps/{appId}/sessions (JWT)", null, appId);
        JwtVerifier.Customer c = customer(auth, appId);
        MiniApp app = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Mini app " + appId + " not found"));
        if (!app.enabled || app.clientId == null) throw ApiException.conflict("APP_DISABLED", app.name + " is not available yet");
        Partner partner = partners.findById(app.clientId).orElseThrow();

        AppSession s = new AppSession();
        s.sessionId = "ses_" + Hmac.random(10);
        s.appId = appId;
        s.customerId = c.id();
        s.createdAt = Instant.now();
        sessions.save(s);
        return Json.obj("sessionId", s.sessionId, "appId", appId, "appName", app.name,
                "url", partner.entryUrl, "allowedOrigins", partner.origins());
    }

    @GetMapping("/mini-apps/{appId}/consents")
    public Map<String, Object> consent(@PathVariable String appId,
                                       @RequestParam(defaultValue = "profile") String scopes,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        JwtVerifier.Customer c = customer(auth, appId);
        MiniApp app = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Mini app " + appId + " not found"));
        Set<String> requested = parseScopes(Arrays.asList(scopes.split(",")));
        List<Map<String, Object>> labels = requested.stream()
                .map(s -> Json.obj("scope", s, "label", SCOPE_LABELS.get(s))).toList();
        return Json.obj("granted", hasConsent(c.id(), appId, requested), "partnerName", app.name, "scopes", labels);
    }

    @PostMapping("/mini-apps/{appId}/auth-codes")
    public Map<String, Object> authCode(@PathVariable String appId, @RequestBody JsonNode body,
                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        trace.record(13, "POST /client/mini-apps/{appId}/auth-codes (fresh JWT, scopes)", null, appId);
        JwtVerifier.Customer c = customer(auth, appId);
        String sessionId = Json.required(body, "sessionId");
        AppSession session = sessions.findById(sessionId)
                .filter(s -> s.customerId.equals(c.id()) && s.appId.equals(appId))
                .orElseThrow(() -> ApiException.forbidden("INVALID_SESSION", "Session does not belong to this customer and app"));
        MiniApp app = apps.findById(session.appId).orElseThrow();

        List<String> raw = new ArrayList<>();
        body.path("scopes").forEach(n -> raw.add(n.asText()));
        Set<String> scopes = parseScopes(raw.isEmpty() ? List.of("profile") : raw);

        String consentId = c.id() + ":" + appId;
        if (body.path("consentGiven").asBoolean(false)) {
            Consent consent = consents.findById(consentId).orElseGet(Consent::new);
            Set<String> all = new LinkedHashSet<>(consent.scopes == null ? List.of() : Arrays.asList(consent.scopes.split(",")));
            all.addAll(scopes);
            consent.id = consentId;
            consent.customerId = c.id();
            consent.appId = appId;
            consent.scopes = String.join(",", all);
            consent.grantedAt = Instant.now();
            consents.save(consent);
        } else if (!hasConsent(c.id(), appId, scopes)) {
            throw ApiException.forbidden("CONSENT_REQUIRED", "Customer has not consented to " + scopes);
        }

        AuthCode code = new AuthCode();
        code.code = "ac_" + Hmac.random(16);
        code.clientId = app.clientId;
        code.appId = appId;
        code.customerId = c.id();
        code.scopes = String.join(",", scopes);
        code.customerName = c.name();
        code.customerPhone = c.phone();
        code.expiresAt = Instant.now().plusSeconds(authCodeTtl);
        authCodes.save(code);
        trace.record(14, "Issue authCode: 1 use, " + authCodeTtl + " s TTL, bound to clientId + customer + scopes",
                "scopes=" + code.scopes, appId);
        return Json.obj("authCode", code.code, "expiresIn", authCodeTtl);
    }

    @GetMapping("/payments/{paymentId}")
    public Map<String, Object> payment(@PathVariable String paymentId,
                                       @RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestHeader(value = "X-Page-Origin", required = false) String pageOrigin) {
        trace.record(27, "GET /client/payments/{paymentId} (fresh bank JWT)", "origin=" + pageOrigin, paymentId);
        JwtVerifier.Customer c = customer(auth, paymentId);
        Payment p = owned(paymentId, c);
        Partner partner = partners.findById(p.clientId).orElseThrow();
        if (pageOrigin == null || !partner.origins().contains(pageOrigin)) {
            trace.record(0, "Rejected: page origin does not belong to this partner", pageOrigin, paymentId);
            throw ApiException.forbidden("ORIGIN_MISMATCH", "Page origin " + pageOrigin + " is not allowed for " + partner.name);
        }
        if (!Payment.PENDING.equals(p.status) || p.expiresAt.isBefore(Instant.now())) {
            throw ApiException.conflict("NOT_PENDING", "Payment is " + p.status);
        }
        return Json.obj("paymentId", p.paymentId, "orderId", p.orderId, "amount", p.amount, "currency", p.currency,
                "merchantName", p.merchantName, "description", p.description, "status", p.status, "expiresAt", p.expiresAt);
    }

    @PostMapping("/payments/{paymentId}/cancel")
    public Map<String, Object> cancel(@PathVariable String paymentId,
                                      @RequestHeader(value = "Authorization", required = false) String auth) {
        trace.record(31, "POST /client/payments/{paymentId}/cancel → CANCELLED", null, paymentId);
        JwtVerifier.Customer c = customer(auth, paymentId);
        Payment p = owned(paymentId, c);
        if (payments.transition(paymentId, Payment.PENDING, Payment.CANCELLED, Instant.now()) == 0) {
            throw ApiException.conflict("NOT_PENDING", "Payment is " + p.status);
        }
        return Json.obj("paymentId", paymentId, "status", Payment.CANCELLED);
    }

    private JwtVerifier.Customer customer(String auth, String ref) {
        try {
            return jwt.verify(auth);
        } catch (ApiException e) {
            trace.record(0, "Rejected bank JWT", e.code + ": " + e.getMessage(), ref);
            throw e;
        }
    }

    private Payment owned(String paymentId, JwtVerifier.Customer c) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " not found"));
        if (!p.customerId.equals(c.id())) throw ApiException.forbidden("WRONG_CUSTOMER", "Payment belongs to another customer");
        return p;
    }

    private boolean hasConsent(String customerId, String appId, Set<String> scopes) {
        return consents.findById(customerId + ":" + appId)
                .map(c -> Arrays.asList(c.scopes.split(",")).containsAll(scopes)).orElse(false);
    }

    private static Set<String> parseScopes(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String scope = s.trim();
            if (scope.isEmpty()) continue;
            if (!SCOPE_LABELS.containsKey(scope)) throw ApiException.badRequest("INVALID_SCOPE", "Unknown scope " + scope);
            out.add(scope);
        }
        return out;
    }
}

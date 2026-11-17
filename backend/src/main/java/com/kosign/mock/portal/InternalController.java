package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mini App Service's internal API (C3), called only by Mobile Backend (and Core Banking for the settlement file).
 * The customer comes from Mobile Backend, which resolved it from the session.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final Signatures signatures;
    private final Http http;
    private final MiniAppService miniApps;
    private final ContentService content;
    private final PaymentService payments;
    private final SettlementService settlement;
    private final String secret;

    public InternalController(Signatures signatures, Http http, MiniAppService miniApps, ContentService content,
                              PaymentService payments, SettlementService settlement, @Value("${mock.bank-secret}") String secret) {
        this.signatures = signatures;
        this.http = http;
        this.miniApps = miniApps;
        this.content = content;
        this.payments = payments;
        this.settlement = settlement;
        this.secret = secret;
    }

    @PostMapping("/mini-apps/list")
    public Map<String, Object> list(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return miniApps.list(Cust.from(b), Json.str(b, "appVersion"), Json.str(b, "etag"));
    }

    @PostMapping("/mini-apps/detail")
    public Map<String, Object> detail(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return miniApps.detail(Cust.from(b), Json.required(b, "appId"));
    }

    @PostMapping("/mini-apps/consent")
    public Map<String, Object> consent(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return miniApps.consent(Cust.from(b), Json.required(b, "appId"), Json.required(b, "consentVersion"));
    }

    @PostMapping("/mini-apps/auth-code")
    public Map<String, Object> authCode(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return miniApps.authCode(Cust.from(b), Json.required(b, "appId"), Json.str(b, "codeChallenge"), Json.str(b, "codeChallengeMethod"));
    }

    @PostMapping("/mini-apps/content")
    public Map<String, Object> content(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return content.content(Cust.from(b), Json.required(b, "appId"), Json.required(b, "operation"), b.path("params"));
    }

    @PostMapping("/mini-apps/orders")
    public Map<String, Object> orders(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return content.createOrder(Cust.from(b), Json.required(b, "appId"), b.path("items"), Json.required(b, "idempotencyKey"));
    }

    @PostMapping("/payments/verify")
    public Map<String, Object> verifyTxn(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return payments.verify(Cust.from(b), Json.required(b, "appId"), b.path("order"), Json.str(b, "checksum"));
    }

    @PostMapping("/payments/get")
    public Map<String, Object> get(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return payments.get(Cust.from(b), Json.required(b, "paymentRef"), String.valueOf(Json.str(b, "purpose")));
    }

    @PostMapping("/payments/init")
    public Map<String, Object> init(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return payments.init(Cust.from(b), Json.required(b, "paymentRef"), Json.required(b, "account"));
    }

    @PostMapping("/payments/confirm")
    public Map<String, Object> confirm(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw, "mobile-backend");
        return payments.confirm(Cust.from(b), Json.required(b, "paymentRef"));
    }

    @PostMapping("/settlement-files")
    public Map<String, Object> settlementFile(HttpServletRequest req, @RequestBody(required = false) String raw) {
        return settlement.reconcile(verify(req, raw, "core-banking"));
    }

    private JsonNode verify(HttpServletRequest req, String raw, String caller) {
        signatures.verify(req, raw, id -> caller.equals(id) ? secret : null);
        return http.parse(raw);
    }
}

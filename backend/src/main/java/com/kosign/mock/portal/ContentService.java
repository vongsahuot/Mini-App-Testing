package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API linkage (no partner code on the device): Mini App Service calls the partner's registered operations
 * with the partnerUserId, checks every answer against the agreed schema and passes on data only.
 */
@Service
public class ContentService {
    /** Agreed schema per operation: the only fields passed on to MB App, all as plain data. */
    private static final Map<String, List<String>> SCHEMA = Map.of(
            "catalog", List.of("itemId", "title", "subtitle", "price", "currency", "poster"),
            "orders", List.of("orderId", "title", "amount", "currency", "status", "ticketCode", "seat", "createdAt"));

    private record Cached(Instant at, Object data) {}

    private final MiniApp.Repo apps;
    private final Partner.Repo partners;
    private final Consent.Repo consents;
    private final ExchangeInfo.Repo exchange;
    private final PartnerGateway gateway;
    private final PaymentService payments;
    private final Trace trace;
    private final Duration timeout, cacheFor;
    private final Map<String, Cached> catalogCache = new ConcurrentHashMap<>();

    public ContentService(MiniApp.Repo apps, Partner.Repo partners, Consent.Repo consents, ExchangeInfo.Repo exchange,
                          PartnerGateway gateway, PaymentService payments, Trace trace,
                          @Value("${mock.partner-api.timeout-ms}") long timeoutMs, @Value("${mock.partner-api.cache-seconds}") long cacheSeconds) {
        this.apps = apps;
        this.partners = partners;
        this.consents = consents;
        this.exchange = exchange;
        this.gateway = gateway;
        this.payments = payments;
        this.trace = trace;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.cacheFor = Duration.ofSeconds(cacheSeconds);
    }

    // ---------- 2:L4–L10, 2:L8a ----------

    public Map<String, Object> content(Cust c, String appId, String operation, JsonNode params) {
        trace.record("MAS", "2:L4", "api", "Request partner content", operation, appId);
        Linked l = linked(c, appId, "2:L5", "operation " + operation);
        if (!l.partner.operationList().contains(operation) || !SCHEMA.containsKey(operation)) {
            throw ApiException.badRequest("UNKNOWN_OPERATION", "Operation " + operation + " is not registered for " + l.partner.name);
        }
        String cacheKey = appId + ":" + operation;
        if (gateway.breakerOpen(l.partner.clientId)) return unavailable(appId, operation, cacheKey, "circuit breaker open");

        trace.record("PG", "2:L6", "api", "Partner API call (partnerUserId, params; signed, timeout ~5 s)", operation, appId);
        Http.Resp r = gateway.outbound(l.partner, PartnerGateway.Target.OPERATION, operation,
                Json.obj("appId", appId, "partnerUserId", l.info.partnerUserId, "params", params), timeout);
        if (!r.ok()) {
            boolean opened = gateway.breakerFailure(l.partner.clientId);
            trace.note("PG", "Partner API failed", (r.status() == 0 ? r.error() : "HTTP " + r.status()) + (opened ? ", breaker opened" : ""), appId);
            return unavailable(appId, operation, cacheKey, r.error());
        }
        gateway.breakerSuccess(l.partner.clientId);
        trace.record("PG", "2:L8", "api", "Data (e.g. products, prices, order history)", null, appId);
        trace.record("MAS", "2:L9", "api", "Data", null, appId);
        List<Map<String, Object>> clean = clean(r.body().path("data"), SCHEMA.get(operation));
        trace.record("MAS", "2:L10", "api", "Validate against the agreed schema; drop unknown fields; data only, never HTML or script",
                clean.size() + " rows", appId);
        if ("catalog".equals(operation)) catalogCache.put(cacheKey, new Cached(Instant.now(), clean));
        return Json.obj("operation", operation, "data", clean, "cached", false);
    }

    private Map<String, Object> unavailable(String appId, String operation, String cacheKey, String why) {
        Cached cached = catalogCache.get(cacheKey);
        boolean fresh = cached != null && cached.at.plus(cacheFor).isAfter(Instant.now());
        trace.record("MAS", "2:L8a", "api", "Circuit breaker counts the failure; return PARTNER_UNAVAILABLE (or cached catalog if still fresh)",
                why + (fresh ? " → cached catalog" : " → PARTNER_UNAVAILABLE"), appId);
        if (fresh) return Json.obj("operation", operation, "data", cached.data, "cached", true);
        throw new ApiException(502, "PARTNER_UNAVAILABLE", "The partner is not answering right now");
    }

    /** Keeps only the agreed fields as plain text / numbers; markup is never passed on. */
    static List<Map<String, Object>> clean(JsonNode rows, List<String> fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!rows.isArray()) throw new ApiException(502, "BAD_PARTNER_DATA", "Partner answer does not match the agreed schema");
        for (JsonNode row : rows) {
            Map<String, Object> m = Json.obj();
            for (String f : fields) {
                JsonNode v = row.get(f);
                if (v == null || v.isNull()) continue;
                if (v.isNumber()) m.put(f, v.decimalValue());
                else if (v.isTextual()) m.put(f, v.asText().replaceAll("<[^>]*>", "").replace("<", "").replace(">", ""));
            }
            out.add(m);
        }
        return out;
    }

    // ---------- 3:L18–L25, 3:L24a ----------

    public Map<String, Object> createOrder(Cust c, String appId, JsonNode items, String idempotencyKey) {
        trace.record("MAS", "3:L18", "api", "Create order", idempotencyKey, appId);
        Linked l = linked(c, appId, null, null);
        Map<String, Object> body = Json.obj("appId", appId, "partnerUserId", l.info.partnerUserId, "items", items, "idempotencyKey", idempotencyKey);
        Http.Resp r = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            trace.record("PG", "3:L19", "api", "Create Order API (partnerUserId, items, idempotencyKey; signed, timeout ~5 s)",
                    attempt == 1 ? idempotencyKey : "retry with the same idempotencyKey", appId);
            r = gateway.outbound(l.partner, PartnerGateway.Target.CREATE_ORDER, null, body, timeout);
            if (!r.timedOut()) break;
            trace.note("PG", "Create Order timed out", "attempt " + attempt, appId);
        }
        if (!r.ok()) {
            trace.record("MAS", "3:L24a", "api", "Save no txn; on timeout retry once with the same idempotencyKey, then return error",
                    r.status() == 0 ? r.error() : "HTTP " + r.status() + " " + r.error(), appId);
            throw new ApiException(502, "PARTNER_UNAVAILABLE", "The partner could not create the order");
        }
        trace.record("PG", "3:L22", "api", "Order info + checksum", Json.str(r.body().path("order"), "orderId"), appId);
        trace.record("MAS", "3:L23", "api", "Order info + checksum", null, appId);
        Payment p;
        try {
            p = payments.sealApiOrder(c, l.app, r.body().path("order"), Json.str(r.body(), "checksum"));
        } catch (ApiException e) {
            trace.record("MAS", "3:L24a", "api", "Save no txn: " + e.code, e.getMessage(), appId);
            throw new ApiException(502, e.code, e.getMessage());
        }
        return PaymentService.info(p);
    }

    private record Linked(MiniApp app, Partner partner, ExchangeInfo info) {}

    private Linked linked(Cust c, String appId, String step, String detail) {
        MiniApp a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Mini App " + appId + " not found"));
        if (!MiniApp.API.equals(a.type)) throw ApiException.badRequest("WRONG_TYPE", appId + " is not an API linkage Mini App");
        if (!a.enabled) throw ApiException.conflict("APP_DISABLED", a.name + " is not available right now");
        Consent consent = consents.findById(Consent.id(c.id(), appId)).orElse(null);
        ExchangeInfo info = exchange.findById(Consent.id(c.id(), appId)).orElse(null);
        boolean ok = consent != null && a.consentVersion.equals(consent.version) && info != null;
        if (step != null) {
            trace.record("MAS", step, "api", "Consent current + registered? Look up partnerUserId; operation must be on this partner's registered list",
                    ok ? detail + ", " + info.partnerUserId : "consent or registration missing", appId);
        }
        if (!ok) throw ApiException.forbidden(consent == null || !a.consentVersion.equals(consent.version) ? "CONSENT_REQUIRED" : "NOT_REGISTERED",
                "Consent and registration are needed first");
        return new Linked(a, partners.findById(a.clientId).orElseThrow(), info);
    }

    public void clearCache() {
        catalogCache.clear();
    }
}

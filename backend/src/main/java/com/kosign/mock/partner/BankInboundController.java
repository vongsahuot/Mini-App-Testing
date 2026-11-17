package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Es256;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Bank → partner (C7). Every call must carry the bank's client certificate (mock) and a valid ES256 signature,
 * verified with the bank's public key from onboarding.
 */
@RestController
@RequestMapping("/bank/v1")
public class BankInboundController {
    private static final String BANK_CLIENT_ID = "mb-bank";

    private final Es256 es256;
    private final Http http;
    private final BankApi bank;
    private final PartnerUser.Repo users;
    private final PartnerOrder.Repo orders;
    private final ProcessedEvent.Repo events;
    private final OrderService orderService;
    private final PartnerChaos chaos;
    private final Trace trace;

    public BankInboundController(Es256 es256, Http http, BankApi bank, PartnerUser.Repo users, PartnerOrder.Repo orders,
                                 ProcessedEvent.Repo events, OrderService orderService, PartnerChaos chaos, Trace trace) {
        this.es256 = es256;
        this.http = http;
        this.bank = bank;
        this.users = users;
        this.orders = orders;
        this.events = events;
        this.orderService = orderService;
        this.chaos = chaos;
        this.trace = trace;
    }

    /** 2:16 Register User: same bankUserRef for the same appId → the same partnerUserId. */
    @PostMapping("/users/register")
    public synchronized Map<String, Object> register(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String appId = Json.required(b, "appId");
        String type = orderService.typeOf(appId);
        trace.record("PS", "2:16", type, "Forward to registered registerUserUrl (egress allowlist, mTLS)", "fields " + fieldNames(b.path("userInfo")), appId);
        if (chaos.registerFail) {
            trace.note("PS", "Chaos: registration answers 500", "registerFail", appId);
            throw new ApiException(500, "CHAOS", "Simulated registration outage");
        }
        String ref = Json.required(b, "bankUserRef");
        PartnerUser u = users.findByAppIdAndBankUserRef(appId, ref).orElse(null);
        if (u == null) {
            u = new PartnerUser();
            u.partnerUserId = "pu_" + Hmac.random(6);
            u.appId = appId;
            u.bankUserRef = ref;
            u.createdAt = Instant.now();
        }
        u.name = Json.str(b.path("userInfo"), "name");
        u.phone = Json.str(b.path("userInfo"), "phone");
        users.save(u);
        return Json.obj("partnerUserId", u.partnerUserId);
    }

    /** 3:51–3:52 Payment notification: verify, update the order once by paymentRef, then ACK. */
    @PostMapping("/payments/notify")
    public synchronized ResponseEntity<Map<String, Object>> notify(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = http.parse(raw);
        String paymentRef = Json.str(b, "paymentRef");
        String eventId = Json.str(b, "eventId");
        trace.record("PS", "3:51", orderService.typeOf(Json.str(b, "appId")), "POST notifyUrl (orderId, paymentRef, amount, currency, status)",
                Json.str(b, "status") + " · " + eventId, paymentRef);
        if (chaos.notifyFailCount > 0) {
            chaos.notifyFailCount--;
            trace.note("PS", "Chaos: answering the notification with 500", chaos.notifyFailCount + " more failure(s) queued", paymentRef);
            return ResponseEntity.status(500).body(Json.obj("code", "CHAOS", "message", "Simulated partner outage"));
        }
        verify(req, raw);
        if (chaos.ignoreNotifications) {
            trace.note("PS", "Chaos: notification acknowledged but ignored", "the order must be fixed by the status inquiry", paymentRef);
            return ResponseEntity.ok(Json.obj("ack", true));
        }
        if (eventId != null && events.existsByEventId(eventId)) {
            trace.record("PS", "3:52", null, "Verify signature; update order once by paymentRef", "duplicate eventId, ignored", paymentRef);
            return ResponseEntity.ok(Json.obj("ack", true));
        }
        PartnerOrder o = orders.findById(String.valueOf(Json.str(b, "orderId"))).orElse(null);
        BigDecimal amount = Json.money(b, "amount");
        String detail;
        if (o == null || amount == null || o.amount.compareTo(amount) != 0 || !o.appId.equals(Json.str(b, "appId"))) {
            detail = "orderId / amount / appId mismatch → not applied";
        } else {
            boolean changed = orderService.applyPaymentStatus(o.orderId, paymentRef, Json.str(b, "status"));
            detail = changed ? "order → " + orders.findById(o.orderId).map(x -> x.status).orElse("?") : "already " + o.status;
        }
        trace.record("PS", "3:52", null, "Verify signature; update order once by paymentRef", detail, paymentRef);
        ProcessedEvent e = new ProcessedEvent();
        e.eventId = eventId;
        e.paymentRef = paymentRef;
        e.status = Json.str(b, "status");
        e.payload = raw;
        e.receivedAt = Instant.now();
        events.save(e);
        return ResponseEntity.ok(Json.obj("ack", true));
    }

    /** 2:L7–L8 API linkage operations: data only (MB App builds the screens). */
    @PostMapping("/operations/{operation}")
    public Map<String, Object> operation(HttpServletRequest req, @PathVariable String operation, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String appId = Json.str(b, "appId");
        trace.record("PS", "2:L7", "api", "Forward to registered apiBaseUrl (egress allowlist, mTLS)", operation, appId);
        if (chaos.apiFail) {
            trace.note("PS", "Chaos: partner API answers 500", "apiFail", appId);
            throw new ApiException(500, "CHAOS", "Simulated partner API outage");
        }
        String partnerUserId = Json.required(b, "partnerUserId");
        Object data = switch (operation) {
            // Extra fields and markup on purpose: the bank must drop unknown fields and pass on plain data only.
            case "catalog" -> OrderService.CATALOG.stream().map(i -> Json.obj("itemId", i.itemId(), "title", i.title(),
                    "subtitle", "<b>" + i.showtime() + "</b>", "price", i.price(), "currency", i.currency(), "poster", i.poster(),
                    "internalCost", i.price().multiply(new BigDecimal("0.6")))).toList();
            case "orders" -> orders.findByPartnerUserIdOrderByCreatedAtDesc(partnerUserId).stream().filter(o -> o.appId.equals(appId))
                    .map(o -> Json.obj("orderId", o.orderId, "title", o.title, "amount", o.amount, "currency", o.currency, "status", o.status,
                            "ticketCode", o.ticketCode, "seat", o.seat, "createdAt", o.createdAt.toString())).toList();
            default -> throw ApiException.notFound("Unknown operation " + operation);
        };
        return Json.obj("data", data);
    }

    /** 3:L20–L22 Create Order for API linkage, idempotent by idempotencyKey. */
    @PostMapping("/orders")
    public Map<String, Object> createOrder(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode b = verify(req, raw);
        String appId = Json.required(b, "appId");
        String key = Json.required(b, "idempotencyKey");
        trace.record("PS", "3:L20", "api", "Forward to registered createOrderUrl (egress allowlist, mTLS)", key, appId);
        PartnerOrder existing;
        synchronized (this) {
            existing = orders.findByAppIdAndIdempotencyKey(appId, key).orElse(null);
            if (existing == null) {
                List<String> items = b.path("items").findValuesAsText("itemId");
                if (items.isEmpty()) throw ApiException.badRequest("INVALID_REQUEST", "items[].itemId is required");
                existing = orderService.create(appId, Json.required(b, "partnerUserId"), items.get(0), key, "3:L21");
                if (chaos.createOrderSlow) {
                    trace.note("PS", "Chaos: first Create Order call is slow", "the bank should time out and retry with the same key", appId);
                    sleep(6000);
                }
            } else {
                trace.note("PS", "Same idempotencyKey → same order", existing.orderId, appId);
            }
        }
        return orderService.sealed(existing);
    }

    private JsonNode verify(HttpServletRequest req, String raw) {
        try {
            es256.verify(req, raw, id -> BANK_CLIENT_ID.equals(id) ? bank.bankCert : null, id -> bank.bankKey);
        } catch (ApiException e) {
            trace.note("PS", "Rejected call claiming to be the bank: " + e.code, e.getMessage(), null);
            throw e;
        }
        return http.parse(raw);
    }

    private static String fieldNames(JsonNode n) {
        StringBuilder b = new StringBuilder();
        n.fieldNames().forEachRemaining(f -> b.append(b.isEmpty() ? "" : ", ").append(f));
        return "[" + b + "]";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

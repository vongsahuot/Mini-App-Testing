package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Signed webhooks from KOSIGN: the source of truth for delivery (47–49), refunds (60) and statements (46). */
@RestController
public class WebhookController {
    private static final String SENDER_ID = "kosign-portal";

    private final Signatures signatures;
    private final Http http;
    private final Kosign kosign;
    private final PartnerOrder.Repo orders;
    private final ProcessedEvent.Repo events;
    private final OrderService orderService;
    private final PartnerChaos chaos;
    private final Trace trace;

    public WebhookController(Signatures signatures, Http http, Kosign kosign, PartnerOrder.Repo orders,
                             ProcessedEvent.Repo events, OrderService orderService, PartnerChaos chaos, Trace trace) {
        this.signatures = signatures;
        this.http = http;
        this.kosign = kosign;
        this.orders = orders;
        this.events = events;
        this.orderService = orderService;
        this.chaos = chaos;
        this.trace = trace;
    }

    @PostMapping("/webhooks/kosign")
    public synchronized ResponseEntity<Map<String, Object>> receive(HttpServletRequest req, @RequestBody(required = false) String raw) {
        JsonNode event = http.parse(raw);
        String type = Json.str(event, "type");
        String eventId = Json.str(event, "eventId");
        JsonNode data = event.path("data");
        String ref = Json.str(data, "paymentId") != null ? Json.str(data, "paymentId") : eventId;
        trace.record("payment.paid".equals(type) || "payment.failed".equals(type) ? 47 : 0, "Webhook received: " + type, eventId, ref);

        if (chaos.webhookFailCount > 0) {
            chaos.webhookFailCount--;
            trace.record(0, "Chaos: answering webhook with 500", chaos.webhookFailCount + " more failure(s) queued", ref);
            return ResponseEntity.status(500).body(Json.obj("code", "CHAOS", "message", "Simulated partner outage"));
        }
        try {
            signatures.verify(req, raw, id -> SENDER_ID.equals(id) ? kosign.secret : null);
        } catch (ApiException e) {
            trace.record(0, "Webhook rejected: " + e.code, e.getMessage(), ref);
            throw e;
        }
        if (chaos.ignoreWebhooks) {
            trace.record(0, "Chaos: webhook acknowledged but ignored", "page must use the status check", ref);
            return ok();
        }
        if (eventId == null || events.existsByEventId(eventId)) {
            trace.record(0, "Duplicate webhook ignored (eventId already processed)", eventId, ref);
            return ok();
        }

        switch (type == null ? "" : type) {
            case "payment.paid", "payment.failed" -> handlePayment(data, ref);
            case "refund.succeeded", "refund.failed" -> {
                orderService.applyRefundTotal(Json.str(data, "orderId"), Json.money(data, "paymentRefundedAmount"));
                trace.record(60, "Webhook " + type, Json.str(data, "refundId") + ", " + Json.money(data, "amount"), ref);
            }
            case "statement.daily" -> trace.record(46, "Partner statement: paid, refunded, fees, net settlement",
                    "paid " + Json.money(data, "paid") + ", refunded " + Json.money(data, "refunded") + ", fees "
                            + Json.money(data, "fees") + ", net " + Json.money(data, "netSettlement"), "statement " + Json.str(data, "date"));
            default -> trace.record(0, "Unknown webhook type", type, ref);
        }

        ProcessedEvent pe = new ProcessedEvent();
        pe.eventId = eventId;
        pe.type = type;
        pe.payload = raw;
        pe.receivedAt = Instant.now();
        events.save(pe);
        return ok();
    }

    private void handlePayment(JsonNode data, String ref) {
        String orderId = Json.str(data, "orderId");
        BigDecimal amount = Json.money(data, "amount");
        PartnerOrder o = orderId == null ? null : orders.findById(orderId).orElse(null);
        if (o == null || amount == null || o.amount.compareTo(amount) != 0 || !o.paymentId.equals(Json.str(data, "paymentId"))) {
            trace.record(48, "Verify failed: orderId / amount mismatch → not delivered", orderId + " " + amount, ref);
            return;
        }
        boolean delivered = orderService.applyPaymentStatus(orderId, Json.str(data, "status"));
        trace.record(48, "Signature, timestamp, nonce, orderId + amount ok → " + (delivered ? "mark paid, deliver once" : "status " + Json.str(data, "status")),
                delivered ? "delivered" : "order already " + orders.findById(orderId).map(x -> x.status).orElse("?"), ref);
    }

    private static ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(Json.obj("ok", true));
    }
}

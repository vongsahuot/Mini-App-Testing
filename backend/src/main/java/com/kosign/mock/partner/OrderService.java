package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Checksum;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Starlight Cinema's orders: prices live here, orders are sealed with the HMAC checksum, the bank is the source of truth. */
@Service
public class OrderService {
    public record Item(String itemId, String title, String showtime, BigDecimal price, String currency, String poster) {}

    public static final List<Item> CATALOG = List.of(
            new Item("m1", "Dune: Part Three", "Tonight 19:30 · Hall 2", new BigDecimal("5.00"), "USD", "🏜️"),
            new Item("m2", "Inside Out 3", "Tomorrow 14:00 · Hall 1", new BigDecimal("4.50"), "USD", "🎈"),
            new Item("m3", "IMAX Premiere", "Sat 21:00 · IMAX", new BigDecimal("25.00"), "USD", "🎬"));

    private final PartnerOrder.Repo orders;
    private final PartnerRefund.Repo refunds;
    private final BankApi bank;
    private final PartnerChaos chaos;
    private final Trace trace;
    private final Map<String, String> appTypes;
    private final long orderTtlMinutes;

    public OrderService(PartnerOrder.Repo orders, PartnerRefund.Repo refunds, BankApi bank, PartnerChaos chaos, Trace trace,
                        @Value("${mock.apps}") String apps, @Value("${mock.order-ttl-minutes}") long orderTtlMinutes) {
        this.orders = orders;
        this.refunds = refunds;
        this.bank = bank;
        this.chaos = chaos;
        this.trace = trace;
        this.appTypes = Arrays.stream(apps.split(",")).map(s -> s.trim().split("="))
                .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
        this.orderTtlMinutes = orderTtlMinutes;
    }

    public String typeOf(String appId) {
        return appId == null ? null : appTypes.get(appId);
    }

    public static Item item(String itemId) {
        return CATALOG.stream().filter(i -> i.itemId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_ITEM", "Unknown itemId " + itemId));
    }

    public static Map<String, Object> view(PartnerOrder o) {
        return Json.obj("orderId", o.orderId, "appId", o.appId, "itemId", o.itemId, "title", o.title, "amount", o.amount,
                "currency", o.currency, "paymentRef", o.paymentRef, "status", o.status, "refundedAmount", o.refundedAmount,
                "ticket", o.ticketCode == null ? null : Json.obj("code", o.ticketCode, "seat", o.seat), "createdAt", o.createdAt);
    }

    /** The order object exactly as sealed by the checksum (docs/CONTRACT.md, "Order checksum"). */
    public Map<String, Object> sealed(PartnerOrder o) {
        return Json.obj("order", Json.obj("appId", o.appId, "orderId", o.orderId, "amount", o.amount, "currency", o.currency,
                        "partnerUserId", o.partnerUserId, "expiry", Checksum.expiry(o.expiry), "description", "1 ticket · " + o.title),
                "checksum", Checksum.sign(chaos.badChecksum ? "wrong-key" : bank.orderKey, o.appId, o.orderId, o.amount, o.currency,
                        o.partnerUserId, o.expiry),
                "title", o.title);
    }

    /** Sets the price on the server and creates the order (3:3, 3:L21, 3:D22). The client only sends an itemId. */
    public PartnerOrder create(String appId, String partnerUserId, String itemId, String idempotencyKey, String noteStep) {
        Item item = item(itemId);
        Instant now = Instant.now();
        PartnerOrder o = new PartnerOrder();
        o.orderId = "ord_" + Hmac.random(6);
        o.appId = appId;
        o.type = typeOf(appId);
        o.itemId = item.itemId();
        o.title = item.title();
        o.amount = item.price();
        o.currency = item.currency();
        o.partnerUserId = partnerUserId;
        o.expiry = now.plusSeconds(orderTtlMinutes * 60);
        o.idempotencyKey = idempotencyKey;
        o.status = PartnerOrder.PENDING_PAYMENT;
        o.createdAt = now;
        o.updatedAt = now;
        orders.save(o);
        trace.record("PS", noteStep, o.type, "Set price; create order (orderId, amount, currency, expiry); HMAC over canonical string",
                item.itemId() + " → " + item.price() + " " + item.currency() + (chaos.badChecksum ? " (chaos: wrong key)" : ""), o.orderId);
        return o;
    }

    /**
     * Applies the bank's payment status once (notification or inquiry). Webview / SDK orders learn their paymentRef
     * here, from the bank. Returns true if it changed the order now.
     */
    public synchronized boolean applyPaymentStatus(String orderId, String paymentRef, String status) {
        PartnerOrder o = orderId != null ? orders.findById(orderId).orElse(null)
                : paymentRef == null ? null : orders.findByPaymentRef(paymentRef).orElse(null);
        if (o == null || !PartnerOrder.PENDING_PAYMENT.equals(o.status)) return false;
        if (o.paymentRef == null) o.paymentRef = paymentRef;
        switch (status) {
            case "SUCCESS" -> {
                o.status = PartnerOrder.DELIVERED;
                o.ticketCode = "TKT-" + Hmac.random(3).toUpperCase();
                o.seat = (char) ('A' + ThreadLocalRandom.current().nextInt(8)) + String.valueOf(1 + ThreadLocalRandom.current().nextInt(16));
            }
            case "FAILED" -> o.status = PartnerOrder.FAILED;
            case "EXPIRED" -> o.status = PartnerOrder.EXPIRED;
            default -> { return false; }
        }
        o.updatedAt = Instant.now();
        orders.save(o);
        return true;
    }

    /** Partner-initiated refund (4:1–4:12). amount null = everything that is left. */
    public synchronized PartnerOrder refund(PartnerOrder o, BigDecimal amount) {
        if (!List.of(PartnerOrder.DELIVERED, PartnerOrder.PARTIALLY_REFUNDED).contains(o.status)) {
            throw ApiException.conflict("NOT_REFUNDABLE", "Order is " + o.status);
        }
        BigDecimal left = o.amount.subtract(o.refundedAmount);
        BigDecimal asked = amount == null ? left : amount;
        PartnerRefund r = new PartnerRefund();
        r.refundId = "rf_" + Hmac.random(8);
        r.orderId = o.orderId;
        r.paymentRef = o.paymentRef;
        r.amount = asked;
        r.status = "REQUESTED";
        r.createdAt = r.updatedAt = Instant.now();
        refunds.save(r);
        Http.Resp resp = bank.post("/refunds", Json.obj("paymentRef", o.paymentRef, "refundId", r.refundId, "amount", asked,
                "reason", amount == null ? "Customer request" : "Partial refund"));
        if (!resp.ok()) {
            r.status = "REJECTED";
            refunds.save(r);
            throw new ApiException(resp.status() == 0 ? 502 : resp.status(), resp.error(), Json.str(resp.body(), "message"));
        }
        trace.record("PS", "4:12", null, "Refund result", Json.str(resp.body(), "status"), o.paymentRef);
        applyRefund(r.refundId, resp.body());
        return orders.findById(o.orderId).orElseThrow();
    }

    /** Applies a refund answer (from the Refund API or Get Refund Status). */
    public synchronized void applyRefund(String refundId, JsonNode b) {
        PartnerRefund r = refunds.findById(refundId).orElse(null);
        if (r == null) return;
        PartnerOrder o = orders.findById(r.orderId).orElseThrow();
        r.status = Json.str(b, "status");
        r.updatedAt = Instant.now();
        refunds.save(r);
        BigDecimal total = Json.money(b, "refundedTotal");
        if (total != null && total.compareTo(o.refundedAmount) > 0) o.refundedAmount = total;
        boolean pending = refunds.findAll().stream().anyMatch(x -> x.orderId.equals(o.orderId) && "REFUND_PENDING".equals(x.status));
        if (pending) o.status = PartnerOrder.REFUND_PENDING;
        else if (o.refundedAmount.signum() > 0) o.status = o.refundedAmount.compareTo(o.amount) >= 0 ? PartnerOrder.REFUNDED : PartnerOrder.PARTIALLY_REFUNDED;
        else o.status = PartnerOrder.DELIVERED;
        o.updatedAt = Instant.now();
        orders.save(o);
    }

    /**
     * Asks the bank for the real status of an order that is still unpaid (47a–47e, L31–L36, D41a–D41f).
     * Returns the updated order.
     */
    public PartnerOrder inquire(PartnerOrder o) {
        if (!PartnerOrder.PENDING_PAYMENT.equals(o.status)) return o;
        o.lastInquiryAt = Instant.now();
        orders.save(o);
        Http.Resp r = bank.post("/payments/inquiry", o.paymentRef != null ? Json.obj("paymentRef", o.paymentRef) : Json.obj("orderId", o.orderId));
        String type = o.type;
        if (!r.ok()) {
            trace.note("PS", "Inquiry failed: " + r.error(), Json.str(r.body(), "message"), o.orderId);
            return o;
        }
        String status = Json.str(r.body(), "status");
        trace.record("PS", key(type, "3:47d", "3:L35", "3:D41e"), type, "Payment status", status, o.orderId);
        boolean changed = applyPaymentStatus(o.orderId, Json.str(r.body(), "paymentRef"), status);
        trace.record("PS", key(type, "3:47e", "3:L36", "3:D41f"), type, "Update order status if final",
                changed ? "→ " + orders.findById(o.orderId).map(x -> x.status).orElse("?") : "not final yet (" + status + ")", o.orderId);
        return orders.findById(o.orderId).orElseThrow();
    }

    static String key(String type, String webSdk, String api, String a2a) {
        return "a2a".equals(type) ? a2a : "api".equals(type) ? api : webSdk;
    }
}

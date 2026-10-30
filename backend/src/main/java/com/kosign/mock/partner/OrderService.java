package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Json;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {
    public record Item(String itemId, String title, String showtime, BigDecimal price, String currency, String poster) {}

    /** Prices live on the partner server; the page only ever sends an itemId. */
    public static final List<Item> CATALOG = List.of(
            new Item("m1", "Dune: Part Three", "Tonight 19:30 · Hall 2", new BigDecimal("5.00"), "USD", "🏜️"),
            new Item("m2", "Inside Out 3", "Tomorrow 14:00 · Hall 1", new BigDecimal("4.50"), "USD", "🎈"),
            new Item("m3", "Expensive IMAX", "Sat 21:00 · IMAX", new BigDecimal("25.00"), "USD", "🎬"));

    private final PartnerOrder.Repo orders;
    private final Kosign kosign;

    public OrderService(PartnerOrder.Repo orders, Kosign kosign) {
        this.orders = orders;
        this.kosign = kosign;
    }

    public static Item item(String itemId) {
        return CATALOG.stream().filter(i -> i.itemId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_ITEM", "Unknown itemId " + itemId));
    }

    public static Map<String, Object> view(PartnerOrder o) {
        return Json.obj("orderId", o.orderId, "itemId", o.itemId, "title", o.title, "amount", o.amount, "currency", o.currency,
                "paymentId", o.paymentId, "status", o.status, "refundedAmount", o.refundedAmount,
                "ticket", o.ticketCode == null ? null : Json.obj("code", o.ticketCode, "seat", o.seat),
                "createdAt", o.createdAt);
    }

    /** Applies a KOSIGN payment status (from a webhook or a status check). Returns true if it delivered now. */
    public synchronized boolean applyPaymentStatus(String orderId, String status) {
        PartnerOrder o = orders.findById(orderId).orElse(null);
        if (o == null || !PartnerOrder.PENDING_PAYMENT.equals(o.status)) return false;
        switch (status) {
            case "PAID" -> {
                o.status = PartnerOrder.DELIVERED; // deliver once: only from PENDING_PAYMENT
                o.ticketCode = "TKT-" + Hmac.random(3).toUpperCase();
                o.seat = (char) ('A' + ThreadLocalRandom.current().nextInt(8)) + String.valueOf(1 + ThreadLocalRandom.current().nextInt(16));
            }
            case "FAILED" -> o.status = PartnerOrder.FAILED;
            case "CANCELLED", "EXPIRED" -> o.status = PartnerOrder.CANCELLED;
            default -> { return false; }
        }
        o.updatedAt = Instant.now();
        orders.save(o);
        return PartnerOrder.DELIVERED.equals(o.status);
    }

    /** Applies KOSIGN's running refund total for the payment (absolute, so webhook + response can both apply it). */
    public synchronized void applyRefundTotal(String orderId, BigDecimal refundedTotal) {
        PartnerOrder o = orders.findById(orderId).orElse(null);
        if (o == null || refundedTotal == null) return;
        if (refundedTotal.compareTo(o.refundedAmount) > 0) o.refundedAmount = refundedTotal;
        if (o.refundedAmount.signum() > 0) {
            o.status = o.refundedAmount.compareTo(o.amount) >= 0 ? PartnerOrder.REFUNDED : PartnerOrder.PARTIALLY_REFUNDED;
        } else if (PartnerOrder.REFUND_PENDING.equals(o.status)) {
            o.status = PartnerOrder.DELIVERED;
        }
        o.updatedAt = Instant.now();
        orders.save(o);
    }

    public synchronized PartnerOrder refund(PartnerOrder o, BigDecimal amount, String reason) {
        if (!List.of(PartnerOrder.DELIVERED, PartnerOrder.PAID, PartnerOrder.PARTIALLY_REFUNDED).contains(o.status)) {
            throw ApiException.conflict("NOT_REFUNDABLE", "Order is " + o.status);
        }
        BigDecimal remaining = o.amount.subtract(o.refundedAmount);
        String refundId = "rf_" + Hmac.random(8);
        var r = kosign.post("/api/v1/partner/payments/" + o.paymentId + "/refunds",
                Json.obj("refundId", refundId, "amount", amount == null ? remaining : amount,
                        "reason", reason == null ? "Customer request" : reason));
        if (!r.ok()) throw new ApiException(r.status() == 0 ? 502 : r.status(), r.error(), Json.str(r.body(), "message"));
        JsonNode b = r.body();
        if ("REFUND_PENDING".equals(Json.str(b, "status"))) {
            o.status = PartnerOrder.REFUND_PENDING;
            o.updatedAt = Instant.now();
            orders.save(o);
        } else {
            applyRefundTotal(o.orderId, Json.money(b, "paymentRefundedAmount"));
        }
        return orders.findById(o.orderId).orElseThrow();
    }
}

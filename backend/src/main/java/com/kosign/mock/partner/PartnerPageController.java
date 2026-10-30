package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API for the partner's own web page (runs in the bank app's WebView). */
@RestController
@RequestMapping("/api")
public class PartnerPageController {
    private final PartnerSession.Repo sessions;
    private final PartnerOrder.Repo orders;
    private final OrderService orderService;
    private final Kosign kosign;
    private final Trace trace;

    public PartnerPageController(PartnerSession.Repo sessions, PartnerOrder.Repo orders, OrderService orderService,
                                 Kosign kosign, Trace trace) {
        this.sessions = sessions;
        this.orders = orders;
        this.orderService = orderService;
        this.kosign = kosign;
        this.trace = trace;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        String authCode = Json.required(body, "authCode");
        trace.record(17, "authCode received from partner page", null, null);
        Http.Resp r = kosign.post("/api/v1/partner/oauth/token", Json.obj("authCode", authCode));
        if (!r.ok()) {
            trace.record(19, "Token exchange failed", r.error(), null);
            throw new ApiException(r.status() == 0 ? 502 : r.status(), r.error(), Json.str(r.body(), "message"));
        }
        PartnerSession s = new PartnerSession();
        s.token = "pss_" + Hmac.random(16);
        s.partnerCustomerId = Json.str(r.body(), "partnerCustomerId");
        s.name = Json.str(r.body().path("profile"), "name");
        s.phone = Json.str(r.body().path("profile"), "phone");
        s.createdAt = Instant.now();
        sessions.save(s);
        trace.record(19, "Code burned; got pairwise partnerCustomerId + consented fields", s.partnerCustomerId, null);
        return Json.obj("sessionToken", s.token, "partnerCustomerId", s.partnerCustomerId,
                "profile", Json.obj("name", s.name, "phone", s.phone));
    }

    @GetMapping("/catalog")
    public List<OrderService.Item> catalog() {
        return OrderService.CATALOG;
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody JsonNode body, @RequestHeader(value = "Authorization", required = false) String auth) {
        PartnerSession s = session(auth);
        OrderService.Item item = OrderService.item(Json.required(body, "itemId"));
        Instant now = Instant.now();
        PartnerOrder o = new PartnerOrder();
        o.orderId = "ord_" + Hmac.random(6);
        o.itemId = item.itemId();
        o.title = item.title();
        o.amount = item.price();
        o.currency = item.currency();
        o.partnerCustomerId = s.partnerCustomerId;
        o.status = PartnerOrder.PENDING_PAYMENT;
        o.createdAt = now;
        o.updatedAt = now;
        orders.save(o);
        trace.record(21, "itemId received; price decided on server", item.itemId() + " → " + item.price() + " " + item.currency(), o.orderId);

        Http.Resp r = kosign.post("/api/v1/partner/payments", Json.obj("orderId", o.orderId,
                "partnerCustomerId", s.partnerCustomerId, "amount", o.amount, "currency", o.currency,
                "description", "1 ticket · " + item.title()));
        if (!r.ok()) {
            o.status = PartnerOrder.FAILED;
            orders.save(o);
            trace.record(24, "Create payment failed", r.error(), o.orderId);
            throw new ApiException(r.status() == 0 ? 502 : r.status(), r.error(), Json.str(r.body(), "message"));
        }
        o.paymentId = Json.str(r.body(), "paymentId");
        orders.save(o);
        trace.record(24, "paymentId received", o.paymentId, o.orderId);
        return OrderService.view(o);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        PartnerSession s = session(auth);
        return orders.findByPartnerCustomerIdOrderByCreatedAtDesc(s.partnerCustomerId).stream().map(OrderService::view).toList();
    }

    /** Step 51: the page asks for the order. If no webhook has arrived yet, ask KOSIGN directly (52–53). */
    @GetMapping("/orders/{orderId}")
    public Map<String, Object> get(@PathVariable String orderId, @RequestHeader(value = "Authorization", required = false) String auth) {
        PartnerOrder o = owned(orderId, session(auth));
        trace.record(51, "Check order status", o.status, orderId);
        if (PartnerOrder.PENDING_PAYMENT.equals(o.status) && o.paymentId != null) {
            Http.Resp r = kosign.get("/api/v1/partner/payments/" + o.paymentId);
            if (r.ok()) {
                String status = Json.str(r.body(), "status");
                trace.record(53, "KOSIGN payment status: " + status, Json.str(r.body(), "bankTxnId"), orderId);
                if (orderService.applyPaymentStatus(orderId, status)) {
                    trace.record(0, "Delivered via status check (webhook not arrived yet)", null, orderId);
                }
            }
            o = orders.findById(orderId).orElseThrow();
        }
        return OrderService.view(o);
    }

    @PostMapping("/orders/{orderId}/refund")
    public Map<String, Object> refund(@PathVariable String orderId, @RequestBody(required = false) JsonNode body,
                                      @RequestHeader(value = "Authorization", required = false) String auth) {
        PartnerOrder o = owned(orderId, session(auth));
        return OrderService.view(orderService.refund(o, Json.money(body, "amount"), Json.str(body, "reason")));
    }

    private PartnerOrder owned(String orderId, PartnerSession s) {
        return orders.findById(orderId).filter(o -> o.partnerCustomerId.equals(s.partnerCustomerId))
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    private PartnerSession session(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw ApiException.unauthorized("MISSING_TOKEN", "Sign in first");
        return sessions.findById(auth.substring(7).trim())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_SESSION", "Session expired, sign in again"));
    }
}

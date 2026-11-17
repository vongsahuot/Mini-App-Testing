package com.kosign.mock.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Test console endpoints for the partner server. No auth: local mock. */
@RestController
@RequestMapping("/api/v1/admin")
public class PartnerAdminController {
    private final PartnerOrder.Repo orders;
    private final PartnerRefund.Repo refunds;
    private final PartnerUser.Repo users;
    private final PartnerSession.Repo sessions;
    private final ProcessedEvent.Repo events;
    private final OrderService orderService;
    private final PartnerChaos chaos;
    private final Trace trace;

    public PartnerAdminController(PartnerOrder.Repo orders, PartnerRefund.Repo refunds, PartnerUser.Repo users, PartnerSession.Repo sessions,
                                  ProcessedEvent.Repo events, OrderService orderService, PartnerChaos chaos, Trace trace) {
        this.orders = orders;
        this.refunds = refunds;
        this.users = users;
        this.sessions = sessions;
        this.events = events;
        this.orderService = orderService;
        this.chaos = chaos;
        this.trace = trace;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return orders.findAllByOrderByCreatedAtDesc().stream().map(OrderService::view).toList();
    }

    @GetMapping("/users")
    public List<PartnerUser> users() {
        return users.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/refunds")
    public List<PartnerRefund> refunds() {
        return refunds.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/notifications")
    public List<ProcessedEvent> notifications() {
        return events.findAllByOrderByIdDesc();
    }

    @PostMapping("/orders/{orderId}/refund")
    public Map<String, Object> refund(@PathVariable String orderId, @RequestBody(required = false) JsonNode body) {
        PartnerOrder o = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        return OrderService.view(orderService.refund(o, Json.money(body, "amount")));
    }

    /** Clears orders, refunds, sessions, notifications and users (the portal reset clears exchange info too). */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        orders.deleteAll();
        refunds.deleteAll();
        sessions.deleteAll();
        events.deleteAll();
        users.deleteAll();
        trace.clear();
        chaos.reset();
        return Json.obj("ok", true);
    }

    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        return chaos.view();
    }

    @PutMapping("/chaos")
    public Map<String, Object> setChaos(@RequestBody JsonNode b) {
        chaos.apply(b);
        return chaos.view();
    }
}

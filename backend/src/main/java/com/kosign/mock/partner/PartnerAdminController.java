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

@RestController
@RequestMapping("/api/v1/admin")
public class PartnerAdminController {
    private final PartnerOrder.Repo orders;
    private final ProcessedEvent.Repo events;
    private final PartnerSession.Repo sessions;
    private final OrderService orderService;
    private final PartnerChaos chaos;
    private final Trace trace;

    public PartnerAdminController(PartnerOrder.Repo orders, ProcessedEvent.Repo events, PartnerSession.Repo sessions,
                                  OrderService orderService, PartnerChaos chaos, Trace trace) {
        this.orders = orders;
        this.events = events;
        this.sessions = sessions;
        this.orderService = orderService;
        this.chaos = chaos;
        this.trace = trace;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return orders.findAllByOrderByCreatedAtDesc().stream().map(OrderService::view).toList();
    }

    @GetMapping("/statements")
    public List<ProcessedEvent> statements() {
        return events.findByTypeOrderByIdDesc("statement.daily");
    }

    @PostMapping("/orders/{orderId}/refund")
    public Map<String, Object> refund(@PathVariable String orderId, @RequestBody(required = false) JsonNode body) {
        PartnerOrder o = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        return OrderService.view(orderService.refund(o, Json.money(body, "amount"), Json.str(body, "reason")));
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        orders.deleteAll();
        events.deleteAll();
        sessions.deleteAll();
        trace.clear();
        chaos.webhookFailCount = 0;
        chaos.ignoreWebhooks = false;
        return Json.obj("ok", true);
    }

    @GetMapping("/chaos")
    public Map<String, Object> chaos() {
        return Json.obj("webhookFailCount", chaos.webhookFailCount, "ignoreWebhooks", chaos.ignoreWebhooks);
    }

    @PutMapping("/chaos")
    public Map<String, Object> setChaos(@RequestBody JsonNode b) {
        if (b.has("webhookFailCount")) chaos.webhookFailCount = Math.max(0, b.get("webhookFailCount").asInt());
        if (b.has("ignoreWebhooks")) chaos.ignoreWebhooks = b.get("ignoreWebhooks").asBoolean();
        return chaos();
    }
}

package com.kosign.mock.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/trace")
public class TraceController {
    private final Trace trace;

    public TraceController(Trace trace) {
        this.trace = trace;
    }

    @GetMapping
    public List<Trace.Event> list() {
        return trace.list();
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        trace.clear();
        return Json.obj("ok", true);
    }

    /** Clients (MB App, partner page, SDK, partner app) report the steps they receive. */
    @PostMapping
    public Map<String, Object> report(@RequestBody JsonNode body) {
        String step = Json.str(body, "step");
        trace.record(Json.required(body, "actor"), step == null || step.isBlank() ? null : step,
                Json.str(body, "flow"), Json.required(body, "title"), Json.str(body, "detail"), Json.str(body, "ref"));
        return Json.obj("ok", true);
    }
}

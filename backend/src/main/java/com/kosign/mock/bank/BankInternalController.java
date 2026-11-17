package com.kosign.mock.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Mini App Service → Mobile Backend: "notify customer" (3:R6b, 4:9, 4:F7), then the push to the device (C10). */
@RestController
public class BankInternalController {
    private final Signatures signatures;
    private final Http http;
    private final MobileBackend mbe;
    private final Trace trace;
    private final String secret;

    public BankInternalController(Signatures signatures, Http http, MobileBackend mbe, Trace trace, @Value("${mock.bank-secret}") String secret) {
        this.signatures = signatures;
        this.http = http;
        this.mbe = mbe;
        this.trace = trace;
        this.secret = secret;
    }

    @PostMapping("/internal/v1/notify")
    public Map<String, Object> notify(HttpServletRequest req, @RequestBody(required = false) String raw) {
        signatures.verify(req, raw, id -> LocalGatewayController.MAS_CLIENT_ID.equals(id) ? secret : null);
        JsonNode b = http.parse(raw);
        String ref = Json.str(b, "ref");
        trace.record("MBE", Json.str(b, "step"), null, Json.required(b, "stepTitle"), Json.str(b, "title"), ref);
        mbe.push(Json.required(b, "customerId"), Json.required(b, "kind"), Json.required(b, "title"), Json.str(b, "body"), ref,
                Json.str(b, "pushStep"));
        return Json.obj("ok", true);
    }
}

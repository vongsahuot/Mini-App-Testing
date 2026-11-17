package com.kosign.mock.bank;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mobile Backend → Mini App Service (C3): internal, signed, carrying the customer resolved from the session. */
@Component
public class MasClient {
    public static final String CLIENT_ID = "mobile-backend";

    private final Http http;
    private final String portalUrl, secret;
    private final Duration timeout;

    public MasClient(Http http, @Value("${mock.portal-url}") String portalUrl, @Value("${mock.bank-secret}") String secret,
                     @Value("${mock.mas-timeout-ms}") long timeoutMs) {
        this.http = http;
        this.portalUrl = portalUrl;
        this.secret = secret;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public Http.Resp call(String path, Customer c, Map<String, Object> body) {
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.put("customer", Json.obj("id", c.customerId, "name", c.name, "phone", c.phone, "segment", c.segment));
        return http.signed(HttpMethod.POST, portalUrl + "/internal/v1/" + path, b, CLIENT_ID, secret, timeout);
    }

    /** Core Banking → Mini App Service: the daily settlement file. */
    public Http.Resp settlement(Map<String, Object> file) {
        return http.signed(HttpMethod.POST, portalUrl + "/internal/v1/settlement-files", file, "core-banking", secret, Duration.ofSeconds(30));
    }
}

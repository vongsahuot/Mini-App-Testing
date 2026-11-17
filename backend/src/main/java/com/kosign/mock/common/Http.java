package com.kosign.mock.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound HTTP between the mock services. Never throws on HTTP errors: status 0 means unreachable,
 * {@link Resp#timedOut()} means the read timeout passed (the other side may still have done the work).
 */
@Component
public class Http {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public record Resp(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }

        public boolean timedOut() { return status == 0 && "TIMEOUT".equals(Json.str(body, "code")); }

        public String error() {
            if (status == 0) return Json.str(body, "code");
            JsonNode code = body.get("code");
            return code == null ? "HTTP_" + status : code.asText();
        }
    }

    private final Map<Long, RestClient> clients = new ConcurrentHashMap<>();
    private final ObjectMapper om;
    private final Signatures signatures;

    public Http(ObjectMapper om, Signatures signatures) {
        this.om = om;
        this.signatures = signatures;
    }

    /** Internal call signed with the shared HMAC secret (C2, C3, C8). */
    public Resp signed(HttpMethod method, String url, Object body, String clientId, String secret) {
        return signed(method, url, body, clientId, secret, DEFAULT_TIMEOUT);
    }

    public Resp signed(HttpMethod method, String url, Object body, String clientId, String secret, Duration timeout) {
        String json = body == null ? "" : write(body);
        String path = URI.create(url).getRawPath();
        return send(method, url, json, signatures.headers(clientId, secret, method.name(), path, json), timeout);
    }

    public Resp send(HttpMethod method, String url, String json, Map<String, String> headers) {
        return send(method, url, json, headers, DEFAULT_TIMEOUT);
    }

    public Resp send(HttpMethod method, String url, String json, Map<String, String> headers, Duration timeout) {
        try {
            RestClient.RequestBodySpec spec = client(timeout).method(method).uri(URI.create(url)).headers(h -> headers.forEach(h::set));
            if (json != null && !json.isEmpty()) spec = spec.contentType(MediaType.APPLICATION_JSON).body(json);
            return spec.exchange((req, res) -> new Resp(res.getStatusCode().value(), read(res.getBody().readAllBytes())));
        } catch (RestClientException e) {
            boolean timedOut = e.getCause() instanceof SocketTimeoutException;
            return new Resp(0, om.createObjectNode().put("code", timedOut ? "TIMEOUT" : "UNREACHABLE")
                    .put("message", String.valueOf(e.getMessage())));
        }
    }

    private RestClient client(Duration timeout) {
        return clients.computeIfAbsent(timeout.toMillis(), ms -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(ms.intValue());
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    public String write(Object body) {
        try {
            return body instanceof String s ? s : om.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return om.createObjectNode();
        try {
            return om.readTree(raw);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_JSON", "Body is not valid JSON");
        }
    }

    private JsonNode read(byte[] bytes) {
        if (bytes.length == 0) return om.createObjectNode();
        try {
            return om.readTree(bytes);
        } catch (IOException e) {
            return om.createObjectNode().set("raw", new TextNode(new String(bytes)));
        }
    }
}

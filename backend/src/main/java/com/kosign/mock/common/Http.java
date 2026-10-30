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
import java.net.URI;
import java.util.Map;

/** Outbound HTTP between the mock services. Never throws on HTTP errors; status 0 means unreachable. */
@Component
public class Http {
    public record Resp(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }

        public String error() {
            if (status == 0) return "UNREACHABLE";
            JsonNode code = body.get("code");
            return code == null ? "HTTP_" + status : code.asText();
        }
    }

    private final RestClient rest;
    private final ObjectMapper om;
    private final Signatures signatures;

    public Http(ObjectMapper om, Signatures signatures) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.om = om;
        this.signatures = signatures;
    }

    public Resp signed(HttpMethod method, String url, Object body, String clientId, String secret) {
        String json = body == null ? "" : write(body);
        String path = URI.create(url).getRawPath();
        return send(method, url, json, signatures.headers(clientId, secret, method.name(), path, json));
    }

    public Resp send(HttpMethod method, String url, String json, Map<String, String> headers) {
        try {
            RestClient.RequestBodySpec spec = rest.method(method).uri(URI.create(url)).headers(h -> headers.forEach(h::set));
            if (json != null && !json.isEmpty()) spec = spec.contentType(MediaType.APPLICATION_JSON).body(json);
            return spec.exchange((req, res) -> new Resp(res.getStatusCode().value(), read(res.getBody().readAllBytes())));
        } catch (RestClientException e) {
            return new Resp(0, om.createObjectNode().put("code", "UNREACHABLE").put("message", String.valueOf(e.getMessage())));
        }
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

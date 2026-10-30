package com.kosign.mock.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mock of mTLS / private_key_jwt: HMAC-SHA256 over METHOD, PATH, TIMESTAMP, NONCE and BODY,
 * with a 5 minute timestamp window and nonce replay protection.
 */
@Component
public class Signatures {
    public static final long MAX_SKEW_SECONDS = 300;

    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public Map<String, String> headers(String clientId, String secret, String method, String path, String body) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        return Map.of(
                "X-Client-Id", clientId,
                "X-Timestamp", ts,
                "X-Nonce", nonce,
                "X-Signature", Hmac.hex(secret, canonical(method, path, ts, nonce, body)));
    }

    /** Verifies a signed request and returns the caller's client id. */
    public String verify(HttpServletRequest req, String body, Function<String, String> secretFor) {
        String clientId = req.getHeader("X-Client-Id");
        String ts = req.getHeader("X-Timestamp");
        String nonce = req.getHeader("X-Nonce");
        String signature = req.getHeader("X-Signature");
        if (clientId == null || ts == null || nonce == null || signature == null) {
            throw ApiException.unauthorized("MISSING_SIGNATURE", "X-Client-Id, X-Timestamp, X-Nonce and X-Signature are required");
        }
        String secret = secretFor.apply(clientId);
        if (secret == null) throw ApiException.unauthorized("UNKNOWN_CLIENT", "Unknown client " + clientId);

        long now = Instant.now().getEpochSecond();
        long sent;
        try {
            sent = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw ApiException.unauthorized("STALE_TIMESTAMP", "X-Timestamp must be epoch seconds");
        }
        if (Math.abs(now - sent) > MAX_SKEW_SECONDS) {
            throw ApiException.unauthorized("STALE_TIMESTAMP", "Timestamp is more than 5 minutes off");
        }
        String expected = Hmac.hex(secret, canonical(req.getMethod(), req.getRequestURI(), ts, nonce, body));
        if (!Hmac.equal(expected, signature)) throw ApiException.unauthorized("BAD_SIGNATURE", "Signature does not match");

        if (seenNonces.putIfAbsent(clientId + ":" + nonce, now) != null) {
            throw ApiException.unauthorized("NONCE_REUSED", "Nonce was already used");
        }
        if (seenNonces.size() > 10_000) seenNonces.values().removeIf(t -> now - t > MAX_SKEW_SECONDS * 2);
        return clientId;
    }

    private static String canonical(String method, String path, String ts, String nonce, String body) {
        return method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + (body == null ? "" : body);
    }
}

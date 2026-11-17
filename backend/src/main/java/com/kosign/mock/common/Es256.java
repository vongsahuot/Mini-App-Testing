package com.kosign.mock.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ES256 (ECDSA P-256 / SHA-256) request signatures between the partner and the bank (C6, C7).
 * Signed string: METHOD\nPATH\nTIMESTAMP\nNONCE\nBODY; the signature is base64url IEEE-P1363 (r||s).
 * The mTLS client certificate is mocked by the X-Client-Cert fingerprint header.
 */
@Component
public class Es256 {
    public static final long MAX_SKEW_SECONDS = 300;
    private static final String ALG = "SHA256withECDSAinP1363Format";

    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public static PrivateKey privateKey(String base64Pkcs8) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Bad EC private key", e);
        }
    }

    public static PublicKey publicKey(String base64Spki) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Bad EC public key", e);
        }
    }

    public Map<String, String> headers(String clientId, String certFingerprint, PrivateKey key, String method, String path, String body) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        try {
            Signature s = Signature.getInstance(ALG);
            s.initSign(key);
            s.update(canonical(method, path, ts, nonce, body).getBytes(StandardCharsets.UTF_8));
            String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
            return Map.of("X-Client-Id", clientId, "X-Client-Cert", certFingerprint, "X-Timestamp", ts, "X-Nonce", nonce, "X-Signature", sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks the (mock) mTLS client certificate and the ES256 signature; returns the caller's client id. */
    public String verify(HttpServletRequest req, String body, Function<String, String> certFor, Function<String, PublicKey> keyFor) {
        String clientId = req.getHeader("X-Client-Id");
        if (clientId == null) throw ApiException.unauthorized("MISSING_SIGNATURE", "X-Client-Id is required");
        String expectedCert = certFor.apply(clientId);
        if (expectedCert == null) throw ApiException.unauthorized("UNKNOWN_CLIENT", "Unknown client " + clientId);
        String cert = req.getHeader("X-Client-Cert");
        if (cert == null || !Hmac.equal(cert, expectedCert)) {
            throw ApiException.unauthorized("MTLS_REQUIRED", "The client certificate registered for " + clientId + " was not presented");
        }
        String ts = req.getHeader("X-Timestamp"), nonce = req.getHeader("X-Nonce"), sig = req.getHeader("X-Signature");
        if (ts == null || nonce == null || sig == null) {
            throw ApiException.unauthorized("MISSING_SIGNATURE", "X-Timestamp, X-Nonce and X-Signature are required");
        }
        long now = Instant.now().getEpochSecond();
        long sent;
        try {
            sent = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw ApiException.unauthorized("STALE_TIMESTAMP", "X-Timestamp must be epoch seconds");
        }
        if (Math.abs(now - sent) > MAX_SKEW_SECONDS) throw ApiException.unauthorized("STALE_TIMESTAMP", "Timestamp is more than 5 minutes off");
        boolean valid;
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(keyFor.apply(clientId));
            s.update(canonical(req.getMethod(), req.getRequestURI(), ts, nonce, body).getBytes(StandardCharsets.UTF_8));
            valid = s.verify(Base64.getUrlDecoder().decode(sig));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            valid = false;
        }
        if (!valid) throw ApiException.unauthorized("BAD_SIGNATURE", "ES256 signature does not match");
        if (seenNonces.putIfAbsent(clientId + ":" + nonce, now) != null) throw ApiException.unauthorized("NONCE_REUSED", "Nonce was already used");
        if (seenNonces.size() > 10_000) seenNonces.values().removeIf(t -> now - t > MAX_SKEW_SECONDS * 2);
        return clientId;
    }

    private static String canonical(String method, String path, String ts, String nonce, String body) {
        return method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + (body == null ? "" : body);
    }
}

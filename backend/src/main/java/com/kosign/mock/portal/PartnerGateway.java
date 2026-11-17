package com.kosign.mock.portal;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Es256;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.RateLimiter;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public Gateway, partner side (lane PG).
 * Inbound (C6): IP allowlist, mTLS client certificate (mocked by a fingerprint header), rate limit, ES256 signature.
 * Outbound (C7): only to the URLs registered at onboarding (egress allowlist), with the bank's certificate and
 * ES256 signature. Also keeps the API-linkage circuit breaker per partner.
 */
@Component
public class PartnerGateway {
    public static final String BANK_CLIENT_ID = "mb-bank";

    public enum Target { REGISTER, NOTIFY, OPERATION, CREATE_ORDER }

    private record Breaker(int failures, Instant openUntil) {}

    private final Partner.Repo partners;
    private final Es256 es256;
    private final Http http;
    private final RateLimiter rateLimiter;
    private final Trace trace;
    private final PrivateKey bankKey;
    private final String bankCert;
    private final int breakerFailures;
    private final Duration breakerOpen;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public PartnerGateway(Partner.Repo partners, Es256 es256, Http http, RateLimiter rateLimiter, Trace trace,
                          @Value("${mock.bank-private-key}") String bankKey, @Value("${mock.bank-cert}") String bankCert,
                          @Value("${mock.partner-api.breaker-failures}") int breakerFailures,
                          @Value("${mock.partner-api.breaker-open-seconds}") long breakerOpenSeconds) {
        this.partners = partners;
        this.es256 = es256;
        this.http = http;
        this.rateLimiter = rateLimiter;
        this.trace = trace;
        this.bankKey = Es256.privateKey(bankKey);
        this.bankCert = bankCert;
        this.breakerFailures = breakerFailures;
        this.breakerOpen = Duration.ofSeconds(breakerOpenSeconds);
    }

    // ---------- inbound (C6) ----------

    /** Runs the inbound checks in order and returns the calling partner. Rejections are traced and thrown. */
    public Partner inbound(HttpServletRequest req, String body) {
        String clientId = req.getHeader("X-Client-Id");
        Partner p = clientId == null ? null : partners.findById(clientId).orElse(null);
        try {
            if (p == null) throw ApiException.unauthorized("UNKNOWN_CLIENT", "Unknown client " + clientId);
            String ip = req.getRemoteAddr();
            if (p.ips().stream().noneMatch(cidr -> inCidr(ip, cidr))) {
                throw ApiException.forbidden("IP_NOT_ALLOWED", ip + " is not a registered server IP of " + clientId);
            }
            rateLimiter.check("partner:" + clientId);
            es256.verify(req, body, id -> id.equals(p.clientId) ? p.certFingerprint : null, id -> Es256.publicKey(p.publicKey));
            return p;
        } catch (ApiException e) {
            trace.note("PG", "Rejected at the partner gateway: " + e.code, e.getMessage() + " on " + req.getRequestURI(), clientId);
            throw e;
        }
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] addr = InetAddress.getByName(ip).getAddress();
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            if (addr.length != net.length) return false;
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : addr.length * 8;
            for (int i = 0; i < addr.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((addr[i] & mask) != (net[i] & mask)) return false;
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    // ---------- outbound (C7) ----------

    /**
     * Calls one of the partner's registered endpoints. The URL always comes from the registry (egress allowlist);
     * an operation must be on the partner's registered list.
     */
    public Http.Resp outbound(Partner p, Target target, String operation, Object body, Duration timeout) {
        String url = switch (target) {
            case REGISTER -> p.registerUserUrl;
            case NOTIFY -> p.notifyUrl;
            case CREATE_ORDER -> p.createOrderUrl;
            case OPERATION -> {
                if (operation == null || !p.operationList().contains(operation)) {
                    throw ApiException.badRequest("UNKNOWN_OPERATION", "Operation " + operation + " is not registered for " + p.clientId);
                }
                yield p.apiBaseUrl + "/" + operation;
            }
        };
        if (url == null) throw new ApiException(502, "NOT_REGISTERED", "No " + target + " URL registered for " + p.clientId);
        String json = http.write(body);
        Map<String, String> headers = es256.headers(BANK_CLIENT_ID, bankCert, bankKey, "POST", URI.create(url).getRawPath(), json);
        return http.send(HttpMethod.POST, url, json, headers, timeout);
    }

    // ---------- circuit breaker (API linkage) ----------

    public boolean breakerOpen(String clientId) {
        Breaker b = breakers.get(clientId);
        return b != null && b.openUntil != null && b.openUntil.isAfter(Instant.now());
    }

    /** Counts a failure; returns true if this failure opened the breaker. */
    public boolean breakerFailure(String clientId) {
        Breaker b = breakers.merge(clientId, new Breaker(1, null), (old, x) -> new Breaker(old.failures + 1, old.openUntil));
        if (b.failures >= breakerFailures) {
            breakers.put(clientId, new Breaker(0, Instant.now().plus(breakerOpen)));
            return true;
        }
        return false;
    }

    public void breakerSuccess(String clientId) {
        breakers.remove(clientId);
    }

    public Map<String, Object> breakerView(String clientId) {
        Breaker b = breakers.get(clientId);
        return Map.of("failures", b == null ? 0 : b.failures, "open", breakerOpen(clientId));
    }

    public void resetBreakers() {
        breakers.clear();
    }
}

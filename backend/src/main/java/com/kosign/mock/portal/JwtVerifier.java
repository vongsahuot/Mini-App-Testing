package com.kosign.mock.portal;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.Date;
import java.util.Map;

/** Verifies the bank app's short-lived JWT against the bank's JWKS (step 3). */
@Component
public class JwtVerifier {
    public static final String ISSUER = "kb-bank";
    public static final String AUDIENCE = "kosign-portal";

    public record Customer(String id, String name, String phone) {}

    private final Http http;
    private final String bankUrl;
    private volatile JWKSet jwks;

    public JwtVerifier(Http http, @Value("${mock.bank-url}") String bankUrl) {
        this.http = http;
        this.bankUrl = bankUrl;
    }

    public Customer verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw ApiException.unauthorized("MISSING_TOKEN", "Authorization: Bearer <bank JWT> is required");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7).trim());
            JWK key = key(jwt.getHeader().getKeyID());
            if (key == null) throw ApiException.unauthorized("UNKNOWN_KEY", "JWT key id not in bank JWKS");
            if (!jwt.verify(new RSASSAVerifier(key.toRSAKey()))) {
                throw ApiException.unauthorized("BAD_SIGNATURE", "JWT signature is invalid");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(c.getIssuer())) throw ApiException.unauthorized("INVALID_TOKEN", "Wrong issuer");
            if (c.getAudience() == null || !c.getAudience().contains(AUDIENCE)) {
                throw ApiException.unauthorized("INVALID_TOKEN", "Wrong audience");
            }
            if (c.getExpirationTime() == null || c.getExpirationTime().before(new Date())) {
                throw ApiException.unauthorized("TOKEN_EXPIRED", "JWT has expired");
            }
            return new Customer(c.getSubject(), c.getStringClaim("name"), c.getStringClaim("phone_number"));
        } catch (ParseException | JOSEException e) {
            throw ApiException.unauthorized("INVALID_TOKEN", e.getMessage());
        }
    }

    /** Cached JWKS; reloaded when an unknown key id shows up (e.g. the bank restarted with a new key). */
    private JWK key(String kid) throws ParseException {
        JWKSet set = jwks;
        if (set == null || set.getKeyByKeyId(kid) == null) {
            Http.Resp r = http.send(HttpMethod.GET, bankUrl + "/.well-known/jwks.json", "", Map.of());
            if (!r.ok()) throw new ApiException(503, "JWKS_UNAVAILABLE", "Cannot load bank JWKS: " + r.error());
            set = JWKSet.parse(r.body().toString());
            jwks = set;
        }
        return set.getKeyByKeyId(kid);
    }
}

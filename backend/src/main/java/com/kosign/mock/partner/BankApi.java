package com.kosign.mock.partner;

import com.kosign.mock.common.Es256;
import com.kosign.mock.common.Http;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

/** Partner server → bank partner gateway (C6): mTLS certificate (mock) + ES256 signature on every call. */
@Component
public class BankApi {
    private static final String PREFIX = "/gw/partner/v1";

    private final Http http;
    private final Es256 es256;
    private final String portalUrl;
    public final String clientId, cert, bankCert, orderKey;
    private final PrivateKey key;
    public final PublicKey bankKey;

    public BankApi(Http http, Es256 es256, @Value("${mock.portal-url}") String portalUrl, @Value("${mock.client-id}") String clientId,
                   @Value("${mock.partner-cert}") String cert, @Value("${mock.bank-cert}") String bankCert,
                   @Value("${mock.partner-private-key}") String key, @Value("${mock.bank-public-key}") String bankKey,
                   @Value("${mock.partner-order-key}") String orderKey) {
        this.http = http;
        this.es256 = es256;
        this.portalUrl = portalUrl;
        this.clientId = clientId;
        this.cert = cert;
        this.bankCert = bankCert;
        this.key = Es256.privateKey(key);
        this.bankKey = Es256.publicKey(bankKey);
        this.orderKey = orderKey;
    }

    public Http.Resp post(String path, Map<String, Object> body) {
        String json = http.write(body);
        return http.send(HttpMethod.POST, portalUrl + PREFIX + path, json, es256.headers(clientId, cert, key, "POST", PREFIX + path, json));
    }
}

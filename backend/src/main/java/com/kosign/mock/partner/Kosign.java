package com.kosign.mock.partner;

import com.kosign.mock.common.Http;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Signed client for the KOSIGN partner API. */
@Component
public class Kosign {
    private final Http http;
    private final String portalUrl;
    public final String clientId;
    public final String secret;

    public Kosign(Http http, @Value("${mock.portal-url}") String portalUrl,
                  @Value("${mock.client-id}") String clientId, @Value("${mock.client-secret}") String secret) {
        this.http = http;
        this.portalUrl = portalUrl;
        this.clientId = clientId;
        this.secret = secret;
    }

    public Http.Resp post(String path, Object body) {
        return http.signed(HttpMethod.POST, portalUrl + path, body, clientId, secret);
    }

    public Http.Resp get(String path) {
        return http.signed(HttpMethod.GET, portalUrl + path, null, clientId, secret);
    }
}

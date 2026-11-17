package com.kosign.mock.portal;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Mini App Service → bank: Local Gateway (C8, timeout below Mini App Service's own) and Mobile Backend's notify (C3). */
@Component
public class BankClients {
    public static final String CLIENT_ID = "mini-app-service";

    private final Http http;
    private final String bankUrl, secret;
    private final Duration lgTimeout;

    public BankClients(Http http, @Value("${mock.bank-url}") String bankUrl, @Value("${mock.bank-secret}") String secret,
                       @Value("${mock.lg-timeout-ms}") long lgTimeoutMs) {
        this.http = http;
        this.bankUrl = bankUrl;
        this.secret = secret;
        this.lgTimeout = Duration.ofMillis(lgTimeoutMs);
    }

    public Http.Resp lg(String path, Map<String, Object> body) {
        return http.signed(HttpMethod.POST, bankUrl + "/lg/v1/" + path, body, CLIENT_ID, secret, lgTimeout);
    }

    /** Asks Mobile Backend to push a message to the customer; step / pushStep are the trace keys MBE and U record. */
    public void notifyCustomer(String customerId, String kind, String title, String body, String ref,
                               String step, String stepTitle, String pushStep) {
        http.signed(HttpMethod.POST, bankUrl + "/internal/v1/notify", Json.obj("customerId", customerId, "kind", kind,
                "title", title, "body", body, "ref", ref, "step", step, "stepTitle", stepTitle, "pushStep", pushStep), CLIENT_ID, secret);
    }
}

package com.kosign.mock.bank;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Step 39: report PAID / FAILED to KOSIGN, retried until it answers 200. */
@Component
public class ResultReporter {
    public static final String CLIENT_ID = "kb-bank";

    private record Pending(String paymentId, Map<String, Object> body) {}

    private final Queue<Pending> retries = new ConcurrentLinkedQueue<>();
    private final Http http;
    private final Trace trace;
    private final BankChaos chaos;
    private final String portalUrl, secret;

    public ResultReporter(Http http, Trace trace, BankChaos chaos,
                          @Value("${mock.portal-url}") String portalUrl, @Value("${mock.bank-secret}") String secret) {
        this.http = http;
        this.trace = trace;
        this.chaos = chaos;
        this.portalUrl = portalUrl;
        this.secret = secret;
    }

    public void report(String paymentId, String status, String bankTxnId, String reason) {
        if (chaos.dropResultCallback) {
            trace.record(0, "Chaos: result call to KOSIGN dropped", "KOSIGN must recover via status check", paymentId);
            return;
        }
        Pending p = new Pending(paymentId, Json.obj("status", status, "bankTxnId", bankTxnId, "reason", reason));
        if (!send(p)) {
            trace.record(0, "Result call failed, queued for retry", null, paymentId);
            retries.add(p);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void retry() {
        for (int i = retries.size(); i > 0; i--) {
            Pending p = retries.poll();
            if (p != null && !send(p)) retries.add(p);
        }
    }

    private boolean send(Pending p) {
        Http.Resp r = http.signed(HttpMethod.POST, portalUrl + "/api/v1/bank/payments/" + p.paymentId() + "/result",
                p.body(), CLIENT_ID, secret);
        return r.ok();
    }
}

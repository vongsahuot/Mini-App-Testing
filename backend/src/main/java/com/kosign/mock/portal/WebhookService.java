package com.kosign.mock.portal;

import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Signed webhooks to partners (steps 47–49, 60, 46). Delivery is the source of truth,
 * so anything other than a 2xx is retried with exponential backoff for up to 24 h.
 */
@Service
public class WebhookService {
    public static final String SENDER_ID = "kosign-portal";

    private final WebhookDelivery.Repo deliveries;
    private final Partner.Repo partners;
    private final Http http;
    private final Trace trace;
    private final long firstRetrySeconds, maxRetrySeconds;
    private final Duration giveUpAfter;

    public WebhookService(WebhookDelivery.Repo deliveries, Partner.Repo partners, Http http, Trace trace,
                          @Value("${mock.webhook.first-retry-seconds}") long firstRetrySeconds,
                          @Value("${mock.webhook.max-retry-interval-seconds}") long maxRetrySeconds,
                          @Value("${mock.webhook.give-up-after-hours}") long giveUpAfterHours) {
        this.deliveries = deliveries;
        this.partners = partners;
        this.http = http;
        this.trace = trace;
        this.firstRetrySeconds = firstRetrySeconds;
        this.maxRetrySeconds = maxRetrySeconds;
        this.giveUpAfter = Duration.ofHours(giveUpAfterHours);
    }

    public void enqueue(String clientId, String type, String paymentId, Map<String, Object> data) {
        Instant now = Instant.now();
        WebhookDelivery d = new WebhookDelivery();
        d.eventId = "evt_" + Hmac.random(8);
        d.type = type;
        d.clientId = clientId;
        d.paymentId = paymentId;
        d.payload = http.write(Json.obj("eventId", d.eventId, "type", type, "createdAt", now.toString(), "data", data));
        d.status = WebhookDelivery.PENDING;
        d.nextAttemptAt = now;
        d.createdAt = now;
        d.updatedAt = now;
        deliveries.save(d);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDue() {
        for (WebhookDelivery d : deliveries.findByStatusAndNextAttemptAtBeforeOrderById(WebhookDelivery.PENDING, Instant.now().plusMillis(1))) {
            attempt(d);
        }
    }

    private void attempt(WebhookDelivery d) {
        Instant now = Instant.now();
        String ref = d.paymentId != null ? d.paymentId : d.eventId;
        Partner partner = partners.findById(d.clientId).orElse(null);
        if (partner == null) {
            d.status = WebhookDelivery.GAVE_UP;
            deliveries.save(d);
            return;
        }
        if (d.firstAttemptAt == null) d.firstAttemptAt = now;

        Http.Resp r = http.signed(HttpMethod.POST, partner.webhookUrl, d.payload, SENDER_ID, partner.secret);
        d.attempts++;
        d.lastStatusCode = r.status();
        d.updatedAt = now;
        if (r.ok()) {
            d.status = WebhookDelivery.DELIVERED;
            trace.record(49, "200 OK (stop retries)", d.type + " delivered after " + d.attempts + " attempt(s)", ref);
        } else if (d.firstAttemptAt.plus(giveUpAfter).isBefore(now)) {
            d.status = WebhookDelivery.GAVE_UP;
            trace.record(0, "Webhook gave up after 24 h", d.type + ", " + d.attempts + " attempts", ref);
        } else {
            long delay = Math.min(firstRetrySeconds << Math.min(d.attempts - 1, 20), maxRetrySeconds);
            d.nextAttemptAt = now.plusSeconds(delay);
            String got = r.status() == 0 ? "no response" : "HTTP " + r.status();
            trace.record(0, "No 200 → retry with exponential backoff",
                    d.type + " got " + got + ", retry in " + delay + " s (attempt " + d.attempts + ")", ref);
        }
        deliveries.save(d);
    }
}

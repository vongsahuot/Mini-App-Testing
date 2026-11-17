package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Kafka stand-in (lane KF) for payment notifications: the outbox relay publishes to the main topic; the consumer
 * signs and delivers to the partner's registered notifyUrl (3:49–3:54). A failed delivery goes to the next retry
 * topic and the offset is committed, so one slow partner never blocks the others; after the last retry topic the
 * message is dead-lettered and ops are alerted.
 */
@Component
public class KafkaMock {
    private final OutboxEvent.Repo outbox;
    private final TopicMessage.Repo topics;
    private final Payment.Repo payments;
    private final Partner.Repo partners;
    private final PartnerGateway gateway;
    private final Http http;
    private final Ops ops;
    private final Trace trace;
    private final List<Long> retryDelays;
    private final Duration timeout;

    public KafkaMock(OutboxEvent.Repo outbox, TopicMessage.Repo topics, Payment.Repo payments, Partner.Repo partners,
                     PartnerGateway gateway, Http http, Ops ops, Trace trace,
                     @Value("${mock.notification.retry-delays-seconds}") String retryDelays,
                     @Value("${mock.notification.timeout-ms}") long timeoutMs) {
        this.outbox = outbox;
        this.topics = topics;
        this.payments = payments;
        this.partners = partners;
        this.gateway = gateway;
        this.http = http;
        this.ops = ops;
        this.trace = trace;
        this.retryDelays = Arrays.stream(retryDelays.split(",")).map(String::trim).map(Long::parseLong).toList();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** Outbox relay: publishes every unpublished outbox row to the main topic. */
    @Scheduled(fixedDelay = 500, initialDelay = 5000)
    public synchronized void relay() {
        for (OutboxEvent e : outbox.findByPublishedAtIsNullOrderById()) {
            publish(TopicMessage.MAIN, e.eventId, e.paymentRef, e.payload, 0, Instant.now());
            e.publishedAt = Instant.now();
            outbox.save(e);
            trace.record("KF", e.publishStep, null, "Outbox relay publishes payment notification (" + e.status + ")",
                    TopicMessage.MAIN + " · " + e.eventId, e.paymentRef);
        }
    }

    /** Consumer for the main topic and every retry topic (each message becomes due at its availableAt). */
    @Scheduled(fixedDelay = 500, initialDelay = 6000)
    public synchronized void consume() {
        for (TopicMessage m : topics.findByConsumedAtIsNullAndTopicNotAndAvailableAtBeforeOrderById(TopicMessage.DLT, Instant.now().plusMillis(1))) {
            deliver(m);
        }
    }

    private void deliver(TopicMessage m) {
        String ref = m.paymentRef;
        JsonNode payload = http.parse(m.payload);
        trace.record("MAS", "3:49", null, m.attempt == 0 ? "Consume payment notification (main topic)" : "Consume payment notification (" + m.topic + ")",
                Json.str(payload, "status") + " · " + m.eventId, ref);
        Payment p = payments.findById(ref).orElse(null);
        Partner partner = p == null ? null : partners.findById(p.clientId).orElse(null);
        if (partner == null) {
            commit(m, "DROPPED: unknown payment or partner", null);
            return;
        }
        trace.record("MAS", "3:49a", null, "Load partner's registered notifyUrl + bank signing key; sign body (timestamp + nonce)", partner.notifyUrl, ref);
        trace.record("PG", "3:50", null, "Payment notification (signed) → registered notifyUrl", Json.str(payload, "status"), ref);
        trace.record("PG", "3:50a", null, "Partner gateway (outbound): egress allowlist, mTLS client cert, timeout " + timeout.toSeconds() + " s", null, ref);
        Http.Resp r = gateway.outbound(partner, PartnerGateway.Target.NOTIFY, null, m.payload, timeout);
        boolean ack = r.ok() && r.body().path("ack").asBoolean(false);
        if (ack) {
            trace.record("PG", "3:53", null, "ACK (HTTP 200 + {\"ack\":true})", "attempt " + (m.attempt + 1), ref);
            trace.record("MAS", "3:54", null, "ACK", m.attempt == 0 ? "delivered" : "delivered from " + m.topic, ref);
            commit(m, "ACK", r.status());
            return;
        }
        String why = r.status() == 0 ? r.error() : "HTTP " + r.status() + (r.ok() ? " without ack" : "");
        commit(m, "NO_ACK: " + why, r.status());
        int next = m.attempt + 1;
        if (next <= retryDelays.size()) {
            long delay = retryDelays.get(next - 1);
            publish(TopicMessage.retryTopic(next), m.eventId, ref, m.payload, next, Instant.now().plusSeconds(delay));
            trace.record("KF", "3:54a", null, "Publish to next retry topic", TopicMessage.retryTopic(next) + " in " + delay + " s (" + why + ")", ref);
            trace.record("MAS", "3:54b", null, "Commit offset; main consumer keeps going", m.topic, ref);
        } else {
            publish(TopicMessage.DLT, m.eventId, ref, m.payload, next, Instant.now());
            trace.record("MAS", "3:54b", null, "Commit offset; main consumer keeps going", m.topic, ref);
            ops.alert("NOTIFICATION_DLT", ref, "Payment notification " + m.eventId + " was not acknowledged after " + next + " attempts (" + why + ")");
            trace.record("MAS", "3:54c", null, "After last retry → dead-letter topic + alert ops", m.eventId, ref);
        }
    }

    private void publish(String topic, String eventId, String paymentRef, String payload, int attempt, Instant availableAt) {
        TopicMessage t = new TopicMessage();
        t.topic = topic;
        t.eventId = eventId;
        t.paymentRef = paymentRef;
        t.payload = payload;
        t.attempt = attempt;
        t.availableAt = availableAt;
        t.createdAt = Instant.now();
        topics.save(t);
    }

    private void commit(TopicMessage m, String result, Integer status) {
        m.consumedAt = Instant.now();
        m.result = result;
        m.lastStatusCode = status;
        topics.save(m);
    }
}

package com.kosign.mock.portal;

import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** State changes that must be atomic: the conditional status update and its outbox row commit together. */
@Component
public class PaymentStore {
    private final Payment.Repo payments;
    private final OutboxEvent.Repo outbox;
    private final Http http;

    public PaymentStore(Payment.Repo payments, OutboxEvent.Repo outbox, Http http) {
        this.payments = payments;
        this.outbox = outbox;
        this.http = http;
    }

    /**
     * Moves the payment from one of {@code from} to a final status and writes the outbox row in the same DB
     * transaction. Returns false (and writes nothing) if the payment was not in one of those states.
     */
    @Transactional
    public boolean finish(String paymentRef, Collection<String> from, String to, String bankTxnId, String failReason, String publishStep) {
        Instant now = Instant.now();
        if (payments.finish(paymentRef, from, to, bankTxnId, failReason, now) == 0) return false;
        Payment p = payments.findById(paymentRef).orElseThrow();
        OutboxEvent e = new OutboxEvent();
        e.eventId = "evt_" + Hmac.random(8);
        e.paymentRef = paymentRef;
        e.status = to;
        e.publishStep = publishStep;
        e.payload = http.write(Json.obj("eventId", e.eventId, "paymentRef", p.paymentRef, "orderId", p.orderId, "appId", p.appId,
                "amount", p.amount, "currency", p.currency, "status", to, "failReason", failReason));
        e.createdAt = now;
        outbox.save(e);
        return true;
    }

    @Transactional
    public boolean initiate(String paymentRef, String account) {
        return payments.initiate(paymentRef, account, Instant.now()) == 1;
    }

    @Transactional
    public boolean process(String paymentRef) {
        return payments.process(paymentRef, Instant.now()) == 1;
    }

    @Transactional
    public boolean transition(String paymentRef, String from, String to) {
        return payments.transition(paymentRef, from, to, Instant.now()) == 1;
    }

    public static final List<String> OPEN = List.of(Payment.PENDING, Payment.INITIATED);
    public static final List<String> PROCESSING = List.of(Payment.PROCESSING);
}

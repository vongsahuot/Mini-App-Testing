package com.kosign.mock.partner;

import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The partner never trusts device callbacks: orders still unpaid after a while are checked with the bank's
 * inquiry API (3:47a, 3:L31, 3:D41a), and pending refunds are polled with Get Refund Status (4:13–4:16).
 */
@Component
public class PartnerJobs {
    private final PartnerOrder.Repo orders;
    private final PartnerRefund.Repo refunds;
    private final OrderService orderService;
    private final BankApi bank;
    private final Trace trace;
    private final Duration inquiryAfter;

    public PartnerJobs(PartnerOrder.Repo orders, PartnerRefund.Repo refunds, OrderService orderService, BankApi bank, Trace trace,
                       @Value("${mock.inquiry-after-seconds}") long inquiryAfterSeconds) {
        this.orders = orders;
        this.refunds = refunds;
        this.orderService = orderService;
        this.bank = bank;
        this.trace = trace;
        this.inquiryAfter = Duration.ofSeconds(inquiryAfterSeconds);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15_000)
    public void checkOrders() {
        Instant now = Instant.now();
        for (PartnerOrder o : orders.findByStatusAndCreatedAtBefore(PartnerOrder.PENDING_PAYMENT, now.minus(inquiryAfter))) {
            if (o.lastInquiryAt != null && o.lastInquiryAt.isAfter(now.minusSeconds(15))) continue;
            if (o.expiry != null && o.expiry.plusSeconds(3600).isBefore(now)) continue; // long gone; the bank expired it
            trace.note("PS", "Order still unpaid after " + inquiryAfter.toSeconds() + " s → ask the bank", o.status, o.orderId);
            orderService.inquire(o);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 17_000)
    public void checkRefunds() {
        for (PartnerRefund r : refunds.findByStatus("REFUND_PENDING")) {
            Http.Resp resp = bank.post("/refunds/status", Json.obj("paymentRef", r.paymentRef, "refundId", r.refundId));
            if (!resp.ok()) continue;
            trace.record("PS", "4:16", null, "Refund status", Json.str(resp.body(), "status"), r.paymentRef);
            orderService.applyRefund(r.refundId, resp.body());
        }
    }
}

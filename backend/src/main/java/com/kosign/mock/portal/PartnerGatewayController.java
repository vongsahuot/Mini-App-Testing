package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public Gateway, partner side, inbound (C6): partner server → Mini App Service. Records the PG steps. */
@RestController
@RequestMapping("/gw/partner/v1")
public class PartnerGatewayController {
    private static final String CHECKS = "Partner gateway (inbound): IP allowlist + mTLS for this clientId, rate limit → Mini App Service";

    private final PartnerGateway gateway;
    private final MiniAppService miniApps;
    private final PaymentService payments;
    private final RefundService refunds;
    private final Payment.Repo paymentRepo;
    private final Http http;
    private final Trace trace;

    public PartnerGatewayController(PartnerGateway gateway, MiniAppService miniApps, PaymentService payments, RefundService refunds,
                                    Payment.Repo paymentRepo, Http http, Trace trace) {
        this.gateway = gateway;
        this.miniApps = miniApps;
        this.payments = payments;
        this.refunds = refunds;
        this.paymentRepo = paymentRepo;
        this.http = http;
        this.trace = trace;
    }

    @PostMapping("/auth/exchange")
    public Map<String, Object> exchange(HttpServletRequest req, @RequestBody(required = false) String raw) {
        Partner p = gateway.inbound(req, raw);
        JsonNode b = http.parse(raw);
        String code = Json.str(b, "authCode");
        String type = miniApps.codeType(code);
        boolean a2a = MiniApp.A2A.equals(type);
        trace.record("PG", a2a ? "2:A17" : "2:37", type, a2a ? "Exchange Auth Code API (authCode, codeVerifier; mTLS, signed)"
                : "Exchange Auth Code API (mTLS, signed)", p.clientId, null);
        trace.record("PG", a2a ? "2:A17a" : "2:37a", type, CHECKS, "ok", null);
        try {
            Map<String, Object> r = miniApps.exchange(p, code, Json.str(b, "codeVerifier"));
            trace.record("PG", a2a ? "2:A20" : "2:40", type, "partnerUserId + consented user info", String.valueOf(r.get("partnerUserId")), String.valueOf(r.get("appId")));
            return r;
        } catch (ApiException e) {
            trace.record("PG", a2a ? "2:A19a" : "2:39a", type, "400 invalid_grant", e.getMessage(), null);
            throw e;
        }
    }

    @PostMapping("/payments/inquiry")
    public Map<String, Object> inquiry(HttpServletRequest req, @RequestBody(required = false) String raw) {
        Partner p = gateway.inbound(req, raw);
        JsonNode b = http.parse(raw);
        String paymentRef = Json.str(b, "paymentRef"), orderId = Json.str(b, "orderId");
        String type = paymentRef != null ? paymentRepo.findById(paymentRef).map(x -> x.type).orElse(null)
                : orderId == null ? null : paymentRepo.findByClientIdAndOrderId(p.clientId, orderId).stream().map(x -> x.type).findFirst().orElse(null);
        String ref = paymentRef != null ? paymentRef : orderId;
        trace.record("PG", PaymentService.key(type, "3:47a", "3:L31", "3:D41a"), type,
                "Inquiry Payment Status API (paymentRef or orderId, signed)", null, ref);
        Payment pay = payments.inquiry(p, paymentRef, orderId);
        trace.record("PG", PaymentService.key(type, "3:47c", "3:L34", "3:D41d"), type,
                "Status: PENDING / INITIATED / PROCESSING / SUCCESS / FAILED / EXPIRED", pay.status, ref);
        return PaymentService.partnerView(pay);
    }

    @PostMapping("/payments")
    public Map<String, Object> createPayment(HttpServletRequest req, @RequestBody(required = false) String raw) {
        Partner p = gateway.inbound(req, raw);
        JsonNode b = http.parse(raw);
        String appId = Json.required(b, "appId");
        String orderId = Json.str(b.path("order"), "orderId");
        trace.record("PG", "3:D23", "a2a", "Create Payment API (order, checksum, partnerUserId; mTLS, signed)", orderId, appId);
        trace.record("PG", "3:D23a", "a2a", CHECKS, "ok", appId);
        try {
            Map<String, Object> r = payments.createForPartner(p, appId, b.path("order"), Json.str(b, "checksum"));
            trace.record("PG", "3:D27", "a2a", "paymentRef + payLink (verified HTTPS link, paymentRef only)", String.valueOf(r.get("paymentRef")), orderId);
            return r;
        } catch (ApiException e) {
            trace.record("PG", "3:D25a", "a2a", "Error (errorCode)", e.code, orderId);
            throw e;
        }
    }

    @PostMapping("/refunds")
    public Map<String, Object> refund(HttpServletRequest req, @RequestBody(required = false) String raw) {
        Partner p = gateway.inbound(req, raw);
        JsonNode b = http.parse(raw);
        String paymentRef = Json.required(b, "paymentRef");
        String refundId = Json.required(b, "refundId");
        trace.record("PG", "4:1", null, "Refund API (paymentRef, refundId, amount, reason; signed)", refundId + ", " + Json.money(b, "amount"), paymentRef);
        try {
            Map<String, Object> r = refunds.view(refunds.request(p, paymentRef, refundId, Json.money(b, "amount"), Json.str(b, "reason")));
            trace.record("PG", "4:11", null, "REFUNDED / FAILED / REFUND_PENDING", String.valueOf(r.get("status")), paymentRef);
            return r;
        } catch (ApiException e) {
            trace.note("PG", "Refund rejected: " + e.code, e.getMessage(), paymentRef);
            throw e;
        }
    }

    @PostMapping("/refunds/status")
    public Map<String, Object> refundStatus(HttpServletRequest req, @RequestBody(required = false) String raw) {
        Partner p = gateway.inbound(req, raw);
        JsonNode b = http.parse(raw);
        String paymentRef = Json.required(b, "paymentRef");
        String refundId = Json.required(b, "refundId");
        trace.record("PG", "4:13", null, "Get Refund Status (paymentRef, refundId; signed)", refundId, paymentRef);
        Map<String, Object> r = refunds.view(refunds.status(p, paymentRef, refundId));
        trace.record("PG", "4:15", null, "Refund status", String.valueOf(r.get("status")), paymentRef);
        return r;
    }
}

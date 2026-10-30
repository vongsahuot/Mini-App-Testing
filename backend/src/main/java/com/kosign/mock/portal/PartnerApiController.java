package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import com.kosign.mock.common.Http;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Signatures;
import com.kosign.mock.common.Trace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Partner backend → KOSIGN, every call signed with the partner's secret (+ timestamp + nonce). */
@RestController
@RequestMapping("/api/v1/partner")
public class PartnerApiController {
    private final Signatures signatures;
    private final Http http;
    private final Partner.Repo partners;
    private final AuthCode.Repo authCodes;
    private final PartnerCustomer.Repo partnerCustomers;
    private final Payment.Repo payments;
    private final RefundService refunds;
    private final Trace trace;
    private final String pairwiseSalt;
    private final long paymentTtlMinutes;

    public PartnerApiController(Signatures signatures, Http http, Partner.Repo partners, AuthCode.Repo authCodes,
                                PartnerCustomer.Repo partnerCustomers, Payment.Repo payments, RefundService refunds,
                                Trace trace, @Value("${mock.pairwise-salt}") String pairwiseSalt,
                                @Value("${mock.payment-ttl-minutes}") long paymentTtlMinutes) {
        this.signatures = signatures;
        this.http = http;
        this.partners = partners;
        this.authCodes = authCodes;
        this.partnerCustomers = partnerCustomers;
        this.payments = payments;
        this.refunds = refunds;
        this.trace = trace;
        this.pairwiseSalt = pairwiseSalt;
        this.paymentTtlMinutes = paymentTtlMinutes;
    }

    @PostMapping("/oauth/token")
    public Map<String, Object> token(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String clientId = verify(req, raw);
        trace.record(18, "POST /partner/oauth/token (authCode + client auth)", "client " + clientId + " authenticated", null);
        String codeValue = Json.required(http.parse(raw), "authCode");
        AuthCode code = authCodes.findById(codeValue)
                .filter(c -> c.clientId.equals(clientId))
                .orElseThrow(() -> ApiException.badRequest("INVALID_GRANT", "Unknown authCode for this client"));
        if (code.expiresAt.isBefore(Instant.now())) throw ApiException.badRequest("EXPIRED_CODE", "authCode has expired");
        if (authCodes.burn(codeValue) == 0) throw ApiException.badRequest("CODE_ALREADY_USED", "authCode was already used");

        String pairwise = "pc_" + Hmac.hex(pairwiseSalt, clientId + ":" + code.customerId).substring(0, 24);
        PartnerCustomer pc = new PartnerCustomer();
        pc.partnerCustomerId = pairwise;
        pc.clientId = clientId;
        pc.customerId = code.customerId;
        partnerCustomers.save(pc);

        List<String> scopes = Arrays.asList(code.scopes.split(","));
        Map<String, Object> profile = Json.obj();
        if (scopes.contains("profile")) profile.put("name", code.customerName);
        if (scopes.contains("phone")) profile.put("phone", code.customerPhone);
        return Json.obj("partnerCustomerId", pairwise, "scopes", scopes, "profile", profile);
    }

    @PostMapping("/payments")
    public Map<String, Object> create(HttpServletRequest req, @RequestBody(required = false) String raw) {
        String clientId = verify(req, raw);
        JsonNode b = http.parse(raw);
        String orderId = Json.required(b, "orderId");
        String partnerCustomerId = Json.required(b, "partnerCustomerId");
        BigDecimal amount = Json.money(b, "amount");
        String currency = Json.str(b, "currency") == null ? "USD" : Json.str(b, "currency");
        trace.record(22, "POST /partner/payments (signed + timestamp + nonce)", "orderId=" + orderId + ", " + amount + " " + currency, orderId);
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "amount must be > 0");

        Payment existing = payments.findByClientIdAndOrderId(clientId, orderId).orElse(null);
        if (existing != null) return idempotent(existing, amount);

        PartnerCustomer pc = partnerCustomers.findById(partnerCustomerId).filter(x -> x.clientId.equals(clientId))
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_CUSTOMER", "Unknown partnerCustomerId"));
        Partner partner = partners.findById(clientId).orElseThrow();
        Instant now = Instant.now();
        Payment p = new Payment();
        p.paymentId = "pay_" + Hmac.random(10);
        p.orderId = orderId;
        p.clientId = clientId;
        p.partnerCustomerId = partnerCustomerId;
        p.customerId = pc.customerId;
        p.amount = amount;
        p.currency = currency;
        p.description = Json.str(b, "description");
        p.merchantName = partner.name;
        p.status = Payment.PENDING;
        p.createdAt = now;
        p.updatedAt = now;
        p.expiresAt = now.plusSeconds(paymentTtlMinutes * 60);
        try {
            payments.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            return idempotent(payments.findByClientIdAndOrderId(clientId, orderId).orElseThrow(), amount);
        }
        trace.record(23, "New orderId → save PENDING (" + paymentTtlMinutes + " min TTL, bound to customer + partner)", p.paymentId, orderId);
        return PaymentService.partnerView(p);
    }

    @GetMapping("/payments/{paymentId}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String paymentId) {
        String clientId = verify(req, null);
        trace.record(52, "GET /partner/payments/{paymentId} (status check)", null, paymentId);
        return PaymentService.partnerView(payments.findById(paymentId).filter(p -> p.clientId.equals(clientId))
                .orElseThrow(() -> ApiException.notFound("Payment " + paymentId + " not found")));
    }

    @GetMapping(value = "/payments", params = "orderId")
    public Map<String, Object> byOrder(HttpServletRequest req, @RequestParam String orderId) {
        String clientId = verify(req, null);
        trace.record(52, "GET /partner/payments?orderId= (status check)", null, orderId);
        return PaymentService.partnerView(payments.findByClientIdAndOrderId(clientId, orderId)
                .orElseThrow(() -> ApiException.notFound("No payment for order " + orderId)));
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public Map<String, Object> refund(HttpServletRequest req, @PathVariable String paymentId,
                                      @RequestBody(required = false) String raw) {
        String clientId = verify(req, raw);
        JsonNode b = http.parse(raw);
        String refundId = Json.required(b, "refundId");
        trace.record(55, "POST /partner/payments/{paymentId}/refunds (signed)", refundId + ", amount=" + Json.money(b, "amount"), paymentId);
        return refunds.view(refunds.request(clientId, paymentId, refundId, Json.money(b, "amount"), Json.str(b, "reason")));
    }

    private Map<String, Object> idempotent(Payment existing, BigDecimal amount) {
        if (existing.amount.compareTo(amount) != 0) {
            trace.record(23, "Same orderId, different amount → 409", existing.amount + " vs " + amount, existing.orderId);
            throw ApiException.conflict("AMOUNT_MISMATCH", "orderId already used with amount " + existing.amount);
        }
        trace.record(23, "Same orderId + same amount → return existing payment", existing.paymentId, existing.orderId);
        return PaymentService.partnerView(existing);
    }

    private String verify(HttpServletRequest req, String body) {
        try {
            return signatures.verify(req, body, id -> partners.findById(id).map(p -> p.secret).orElse(null));
        } catch (ApiException e) {
            trace.record(0, "Rejected partner call", e.code + " on " + req.getRequestURI(), null);
            throw e;
        }
    }
}

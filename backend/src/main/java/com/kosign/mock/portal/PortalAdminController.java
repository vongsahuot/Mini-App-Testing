package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test console endpoints, and the Mini App Admin actions (C11). Admin actions need a mock Active Directory account
 * in X-Staff-User ("admin" for configuration, "admin" or "ops" for resolving items) and are audited.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PortalAdminController {
    private final Payment.Repo payments;
    private final Refund.Repo refunds;
    private final OutboxEvent.Repo outbox;
    private final TopicMessage.Repo topics;
    private final Reconciliation.Repo reconciliations;
    private final OpsItem.Repo opsItems;
    private final AuditLog.Repo audit;
    private final MiniApp.Repo apps;
    private final Partner.Repo partners;
    private final Consent.Repo consents;
    private final ExchangeInfo.Repo exchange;
    private final AuthCode.Repo codes;
    private final PortalSeeder seeder;
    private final PaymentJobs jobs;
    private final RefundService refundService;
    private final PartnerGateway gateway;
    private final ContentService content;
    private final Ops ops;
    private final Trace trace;

    public PortalAdminController(Payment.Repo payments, Refund.Repo refunds, OutboxEvent.Repo outbox, TopicMessage.Repo topics,
                                 Reconciliation.Repo reconciliations, OpsItem.Repo opsItems, AuditLog.Repo audit, MiniApp.Repo apps,
                                 Partner.Repo partners, Consent.Repo consents, ExchangeInfo.Repo exchange, AuthCode.Repo codes,
                                 PortalSeeder seeder, PaymentJobs jobs, RefundService refundService, PartnerGateway gateway,
                                 ContentService content, Ops ops, Trace trace) {
        this.payments = payments;
        this.refunds = refunds;
        this.outbox = outbox;
        this.topics = topics;
        this.reconciliations = reconciliations;
        this.opsItems = opsItems;
        this.audit = audit;
        this.apps = apps;
        this.partners = partners;
        this.consents = consents;
        this.exchange = exchange;
        this.codes = codes;
        this.seeder = seeder;
        this.jobs = jobs;
        this.refundService = refundService;
        this.gateway = gateway;
        this.content = content;
        this.ops = ops;
        this.trace = trace;
    }

    @GetMapping("/payments")
    public List<Payment> payments() {
        return payments.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/refunds")
    public List<Refund> refunds() {
        return refunds.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/outbox")
    public List<OutboxEvent> outbox() {
        return outbox.findAllByOrderByIdDesc();
    }

    @GetMapping("/topics")
    public List<TopicMessage> topics() {
        return topics.findAllByOrderByIdDesc();
    }

    @GetMapping("/reconciliations")
    public List<Map<String, Object>> reconciliations() {
        return reconciliations.findAllByOrderByIdDesc().stream().map(SettlementService::view).toList();
    }

    @GetMapping("/ops-items")
    public List<OpsItem> opsItems() {
        return opsItems.findAllByOrderByIdDesc();
    }

    @PostMapping("/ops-items/{id}/resolve")
    public OpsItem resolve(@PathVariable long id, @RequestHeader(value = "X-Staff-User", required = false) String staff,
                           @RequestBody(required = false) JsonNode body) {
        String who = staff(staff, false);
        OpsItem i = opsItems.findById(id).orElseThrow(() -> ApiException.notFound("No ops item " + id));
        i.status = OpsItem.RESOLVED;
        i.resolvedBy = who;
        i.resolution = Json.str(body, "note");
        i.resolvedAt = Instant.now();
        ops.audit(who, "RESOLVE_OPS_ITEM", String.valueOf(id), i.kind + " " + i.ref + (i.resolution == null ? "" : ": " + i.resolution));
        return opsItems.save(i);
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() {
        return audit.findAllByOrderByIdDesc();
    }

    @GetMapping("/mini-apps")
    public List<MiniApp> miniApps() {
        return apps.findAllByOrderBySortOrder();
    }

    @PutMapping("/mini-apps/{appId}")
    public MiniApp updateApp(@PathVariable String appId, @RequestHeader(value = "X-Staff-User", required = false) String staff,
                             @RequestBody JsonNode body) {
        String who = staff(staff, true);
        MiniApp a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("No Mini App " + appId));
        if (body.has("enabled")) a.enabled = body.get("enabled").asBoolean();
        if (body.has("sdkEnabled")) a.sdkEnabled = body.get("sdkEnabled").asBoolean();
        ops.audit(who, "UPDATE_MINI_APP", appId, "enabled=" + a.enabled + ", sdkEnabled=" + a.sdkEnabled);
        return apps.save(a);
    }

    @PostMapping("/mini-apps/{appId}/consent-version")
    public MiniApp bumpConsent(@PathVariable String appId, @RequestHeader(value = "X-Staff-User", required = false) String staff) {
        String who = staff(staff, true);
        MiniApp a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("No Mini App " + appId));
        a.consentVersion = String.valueOf(Integer.parseInt(a.consentVersion) + 1);
        ops.audit(who, "BUMP_CONSENT_VERSION", appId, "now v" + a.consentVersion);
        return apps.save(a);
    }

    @GetMapping("/partners")
    public List<Map<String, Object>> partners() {
        return partners.findAll().stream().map(p -> Json.obj("clientId", p.clientId, "name", p.name, "allowedIps", p.ips(),
                "certFingerprint", p.certFingerprint, "registerUserUrl", p.registerUserUrl, "notifyUrl", p.notifyUrl,
                "apiBaseUrl", p.apiBaseUrl, "createOrderUrl", p.createOrderUrl, "operations", p.operationList(),
                "merchantAccount", p.merchantAccount, "breaker", gateway.breakerView(p.clientId))).toList();
    }

    @PutMapping("/partners/{clientId}")
    public Map<String, Object> updatePartner(@PathVariable String clientId, @RequestHeader(value = "X-Staff-User", required = false) String staff,
                                             @RequestBody JsonNode body) {
        String who = staff(staff, true);
        Partner p = partners.findById(clientId).orElseThrow(() -> ApiException.notFound("No partner " + clientId));
        if (body.has("allowedIps")) {
            JsonNode ips = body.get("allowedIps");
            List<String> list = new java.util.ArrayList<>();
            if (ips.isArray()) ips.forEach(x -> list.add(x.asText()));
            else list.add(ips.asText());
            p.allowedIps = String.join(", ", list);
        }
        partners.save(p);
        ops.audit(who, "UPDATE_PARTNER", clientId, "allowedIps=" + p.allowedIps);
        return Json.obj("clientId", p.clientId, "allowedIps", p.ips());
    }

    @GetMapping("/consents")
    public List<Consent> consents() {
        return consents.findAll();
    }

    @GetMapping("/exchange-info")
    public List<ExchangeInfo> exchangeInfo() {
        return exchange.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/jobs/recovery")
    public Map<String, Object> runRecovery() {
        return Json.obj("checked", jobs.recover(Duration.ZERO));
    }

    @PostMapping("/jobs/expiry")
    public Map<String, Object> runExpiry(@RequestBody(required = false) JsonNode body) {
        boolean force = body != null && body.path("force").asBoolean(false);
        return Json.obj("expired", force ? jobs.expireAllNow() : jobs.expire());
    }

    @PostMapping("/jobs/refund-recovery")
    public Map<String, Object> runRefundRecovery() {
        return Json.obj("checked", refundService.recover(Duration.ZERO));
    }

    /** Clears all transactional data and restores the seeded partner registry and Mini Apps. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        topics.deleteAll();
        outbox.deleteAll();
        refunds.deleteAll();
        payments.deleteAll();
        reconciliations.deleteAll();
        opsItems.deleteAll();
        audit.deleteAll();
        codes.deleteAll();
        exchange.deleteAll();
        consents.deleteAll();
        seeder.run(null);
        gateway.resetBreakers();
        content.clearCache();
        trace.clear();
        return Json.obj("ok", true);
    }

    private static String staff(String user, boolean adminOnly) {
        if (user == null || !(user.equals("admin") || (!adminOnly && user.equals("ops")))) {
            throw ApiException.forbidden("AD_REQUIRED", adminOnly ? "Needs the Active Directory account 'admin' in X-Staff-User"
                    : "Needs an Active Directory account ('admin' or 'ops') in X-Staff-User");
        }
        return user;
    }
}

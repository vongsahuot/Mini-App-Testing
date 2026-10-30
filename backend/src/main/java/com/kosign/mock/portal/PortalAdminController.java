package com.kosign.mock.portal;

import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Test console endpoints. No auth: this is a local mock. */
@RestController
@RequestMapping("/api/v1/admin")
public class PortalAdminController {
    private final Payment.Repo payments;
    private final Refund.Repo refunds;
    private final WebhookDelivery.Repo webhooks;
    private final Reconciliation.Repo reconciliations;
    private final Consent.Repo consents;
    private final AppSession.Repo sessions;
    private final AuthCode.Repo authCodes;
    private final PaymentService paymentService;
    private final Trace trace;

    public PortalAdminController(Payment.Repo payments, Refund.Repo refunds, WebhookDelivery.Repo webhooks,
                                 Reconciliation.Repo reconciliations, Consent.Repo consents, AppSession.Repo sessions,
                                 AuthCode.Repo authCodes, PaymentService paymentService, Trace trace) {
        this.payments = payments;
        this.refunds = refunds;
        this.webhooks = webhooks;
        this.reconciliations = reconciliations;
        this.consents = consents;
        this.sessions = sessions;
        this.authCodes = authCodes;
        this.paymentService = paymentService;
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

    @GetMapping("/webhooks")
    public List<WebhookDelivery> webhooks() {
        return webhooks.findAllByOrderByIdDesc();
    }

    @GetMapping("/reconciliations")
    public List<Map<String, Object>> reconciliations() {
        return reconciliations.findAllByOrderByIdDesc().stream().map(SettlementService::view).toList();
    }

    @PostMapping("/jobs/recovery")
    public Map<String, Object> runRecovery() {
        return Json.obj("checked", paymentService.recover(Duration.ZERO));
    }

    /** Clears transactional data (keeps partners, mini apps and pairwise ids) so a test run starts clean. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        webhooks.deleteAll();
        refunds.deleteAll();
        payments.deleteAll();
        reconciliations.deleteAll();
        consents.deleteAll();
        authCodes.deleteAll();
        sessions.deleteAll();
        trace.clear();
        return Json.obj("ok", true);
    }
}

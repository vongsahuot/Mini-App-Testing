package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Daily settlement file reconciliation (steps 44–46). */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.02");

    private final Payment.Repo payments;
    private final Refund.Repo refunds;
    private final Partner.Repo partners;
    private final Reconciliation.Repo reconciliations;
    private final WebhookService webhooks;
    private final Trace trace;

    public SettlementService(Payment.Repo payments, Refund.Repo refunds, Partner.Repo partners,
                             Reconciliation.Repo reconciliations, WebhookService webhooks, Trace trace) {
        this.payments = payments;
        this.refunds = refunds;
        this.partners = partners;
        this.reconciliations = reconciliations;
        this.webhooks = webhooks;
        this.trace = trace;
    }

    public Map<String, Object> reconcile(JsonNode file) {
        String date = Json.str(file, "date") != null ? Json.str(file, "date") : LocalDate.now().toString();
        List<String> mismatches = new ArrayList<>();
        Set<String> paymentIds = new HashSet<>(), refundIds = new HashSet<>();
        int matched = 0;

        for (JsonNode e : file.path("entries")) {
            String txn = Json.str(e, "bankTxnId");
            BigDecimal amount = Json.money(e, "amount");
            if ("PAYMENT".equals(Json.str(e, "type"))) {
                String id = Json.str(e, "paymentId");
                paymentIds.add(id);
                Payment p = id == null ? null : payments.findById(id).orElse(null);
                if (p == null) mismatches.add("Bank txn " + txn + " debits unknown payment " + id);
                else if (!p.settled()) mismatches.add("Payment " + id + " is " + p.status + " but the bank debited it (" + txn + ")");
                else if (!txn.equals(p.bankTxnId)) mismatches.add("Payment " + id + ": bankTxnId " + p.bankTxnId + " vs bank " + txn);
                else if (amount == null || p.amount.compareTo(amount) != 0) mismatches.add("Payment " + id + ": amount " + p.amount + " vs bank " + amount);
                else matched++;
            } else if ("REFUND".equals(Json.str(e, "type"))) {
                String id = Json.str(e, "refundId");
                refundIds.add(id);
                Refund r = id == null ? null : refunds.findById(id).orElse(null);
                if (r == null) mismatches.add("Bank txn " + txn + " is an unknown refund " + id);
                else if (!Refund.REFUNDED.equals(r.status)) mismatches.add("Refund " + id + " is " + r.status + " but the bank refunded it");
                else if (amount == null || r.amount.compareTo(amount) != 0) mismatches.add("Refund " + id + ": amount " + r.amount + " vs bank " + amount);
                else matched++;
            } else {
                mismatches.add("Unknown entry type in bank file: " + e);
            }
        }
        for (Payment p : payments.findAll()) {
            if (p.settled() && !paymentIds.contains(p.paymentId)) mismatches.add("Payment " + p.paymentId + " is " + p.status + " but missing from the bank file");
        }
        for (Refund r : refunds.findAll()) {
            if (Refund.REFUNDED.equals(r.status) && !refundIds.contains(r.refundId)) mismatches.add("Refund " + r.refundId + " is REFUNDED but missing from the bank file");
        }

        Reconciliation rec = new Reconciliation();
        rec.date = date;
        rec.matched = matched;
        rec.mismatches = mismatches;
        rec.createdAt = Instant.now();
        reconciliations.save(rec);
        trace.record(45, mismatches.isEmpty() ? "Reconciled: all transactions match" : "Reconciled: ALERT ops, " + mismatches.size() + " mismatch(es)",
                matched + " matched" + (mismatches.isEmpty() ? "" : "; " + mismatches.get(0)), "settlement " + date);
        if (!mismatches.isEmpty()) log.warn("ALERT ops: settlement {} has {} mismatches: {}", date, mismatches.size(), mismatches);

        sendStatements(date);
        return view(rec);
    }

    private void sendStatements(String date) {
        for (Partner partner : partners.findAll()) {
            List<Payment> settled = payments.findAll().stream().filter(p -> p.clientId.equals(partner.clientId) && p.settled()).toList();
            BigDecimal paid = settled.stream().map(p -> p.amount).reduce(new BigDecimal("0.00"), BigDecimal::add);
            BigDecimal refunded = settled.stream().map(p -> p.refundedAmount).reduce(new BigDecimal("0.00"), BigDecimal::add);
            BigDecimal fees = paid.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
            webhooks.enqueue(partner.clientId, "statement.daily", null, Json.obj(
                    "date", date, "payments", settled.size(), "paid", paid, "refunded", refunded, "fees", fees,
                    "netSettlement", paid.subtract(refunded).subtract(fees), "currency", "USD"));
        }
    }

    public static Map<String, Object> view(Reconciliation r) {
        return Json.obj("id", r.id, "date", r.date, "matched", r.matched, "mismatches", r.mismatches, "createdAt", r.createdAt);
    }
}

package com.kosign.mock.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.kosign.mock.common.Json;
import com.kosign.mock.common.Trace;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily reconciliation (4:17–4:19): the Core Banking settlement file is matched against every payment and refund.
 * The only automatic change is PROCESSING → SUCCESS when the ledger proves the debit; anything missing is flagged
 * to ops, never auto-failed.
 */
@Service
public class SettlementService {
    private final Payment.Repo payments;
    private final Refund.Repo refunds;
    private final Reconciliation.Repo reports;
    private final PaymentStore store;
    private final BankClients bank;
    private final Ops ops;
    private final Trace trace;

    public SettlementService(Payment.Repo payments, Refund.Repo refunds, Reconciliation.Repo reports, PaymentStore store,
                             BankClients bank, Ops ops, Trace trace) {
        this.payments = payments;
        this.refunds = refunds;
        this.reports = reports;
        this.store = store;
        this.bank = bank;
        this.ops = ops;
        this.trace = trace;
    }

    public synchronized Map<String, Object> reconcile(JsonNode file) {
        String date = Json.str(file, "date") != null ? Json.str(file, "date") : LocalDate.now().toString();
        String ref = "settlement " + date;
        trace.record("MAS", "4:17", null, "Settlement file: bankTxnId, paymentRef / refundId, amount, status", file.path("entries").size() + " entries", ref);
        List<String> mismatches = new ArrayList<>();
        Set<String> paymentTxns = new HashSet<>(), refundIds = new HashSet<>();
        int matched = 0, fixed = 0;

        for (JsonNode e : file.path("entries")) {
            String txn = Json.str(e, "bankTxnId");
            BigDecimal amount = Json.money(e, "amount");
            if ("PAYMENT".equals(Json.str(e, "type"))) {
                String id = Json.str(e, "paymentRef");
                paymentTxns.add(txn);
                Payment p = id == null ? null : payments.findById(id).orElse(null);
                if (p == null) mismatches.add("Bank txn " + txn + " debits unknown payment " + id);
                else if (amount == null || p.amount.compareTo(amount) != 0) mismatches.add("Payment " + id + ": amount " + p.amount + " vs bank " + amount);
                else if (Payment.PROCESSING.equals(p.status)) {
                    if (store.finish(id, PaymentStore.PROCESSING, Payment.SUCCESS, txn, null, "3:R6")) {
                        fixed++;
                        bank.notifyCustomer(p.customerId, "PAYMENT", "Payment successful", p.amount + " " + p.currency + " to " + p.merchantName
                                + " is complete", id, null, "Notify customer (reconciliation fixed PROCESSING → SUCCESS)", null);
                    }
                } else if (!Payment.SUCCESS.equals(p.status)) mismatches.add("Payment " + id + " is " + p.status + " but the bank debited it (" + txn + ")");
                else if (!txn.equals(p.bankTxnId)) mismatches.add("Payment " + id + ": bankTxnId " + p.bankTxnId + " vs bank " + txn);
                else matched++;
            } else if ("REFUND".equals(Json.str(e, "type"))) {
                String id = Json.str(e, "refundId");
                refundIds.add(id);
                Refund r = id == null ? null : refunds.findById(id).orElse(null);
                if (r == null) mismatches.add("Bank txn " + txn + " is an unknown refund " + id);
                else if (amount == null || r.amount.compareTo(amount) != 0) mismatches.add("Refund " + id + ": amount " + r.amount + " vs bank " + amount);
                else if (!Refund.REFUNDED.equals(r.status)) mismatches.add("Refund " + id + " is " + r.status + " but the bank refunded it (" + txn + ")");
                else matched++;
            } else {
                mismatches.add("Unknown entry type in the settlement file: " + e);
            }
        }
        for (Payment p : payments.findAll()) {
            if (Payment.SUCCESS.equals(p.status) && !paymentTxns.contains(p.bankTxnId)) {
                mismatches.add("Payment " + p.paymentRef + " is SUCCESS but missing from the settlement file");
            } else if (Payment.PROCESSING.equals(p.status)) {
                mismatches.add("Payment " + p.paymentRef + " is PROCESSING and not in the file (flagged, not failed)");
            }
        }
        for (Refund r : refunds.findAll()) {
            if (Refund.REFUNDED.equals(r.status) && !refundIds.contains(r.refundId)) {
                mismatches.add("Refund " + r.refundId + " is REFUNDED but missing from the settlement file");
            }
        }
        trace.record("MAS", "4:18", null, "Match payments + refunds. PROCESSING and in file → SUCCESS. Not in file → flag ops. Alert on mismatch",
                matched + " matched, " + fixed + " fixed, " + mismatches.size() + " mismatch(es)", ref);

        Reconciliation rec = new Reconciliation();
        rec.date = date;
        rec.entries = file.path("entries").size();
        rec.matched = matched;
        rec.fixed = fixed;
        rec.mismatches = mismatches;
        rec.createdAt = Instant.now();
        reports.save(rec);
        for (String m : mismatches) ops.alert("RECONCILIATION", date, m);
        trace.record("MAS", "4:19", null, "Write reconciliation report; open items to ops view (audited)",
                mismatches.isEmpty() ? "all matched" : mismatches.size() + " open item(s)", ref);
        return view(rec);
    }

    public static Map<String, Object> view(Reconciliation r) {
        return Json.obj("id", r.id, "date", r.date, "entries", r.entries, "matched", r.matched, "fixed", r.fixed,
                "mismatches", r.mismatches, "createdAt", r.createdAt);
    }
}

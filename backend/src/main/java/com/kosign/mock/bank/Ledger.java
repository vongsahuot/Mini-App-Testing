package com.kosign.mock.bank;

import com.kosign.mock.common.Hmac;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Core Banking: every hold and posting is one database transaction (balances + journal row). */
@Component
public class Ledger {
    /** Serialises postings against voids (see LocalGatewayController). */
    public static final Object LOCK = new Object();

    private final Account.Repo accounts;
    private final Hold.Repo holds;
    private final BankTxn.Repo txns;

    public Ledger(Account.Repo accounts, Hold.Repo holds, BankTxn.Repo txns) {
        this.accounts = accounts;
        this.holds = holds;
        this.txns = txns;
    }

    /** Places a hold; idempotent by paymentRef. Returns null if held, or the refusal reason. */
    @Transactional
    public synchronized String hold(String paymentRef, String accountNo, BigDecimal amount, String currency) {
        Hold existing = holds.findById(paymentRef).orElse(null);
        if (existing != null) return Hold.HELD.equals(existing.status) ? null : "HOLD_" + existing.status;
        Account a = accounts.findById(accountNo).orElse(null);
        if (a == null) return "ACCOUNT_NOT_FOUND";
        if (!a.currency.equals(currency)) return "CURRENCY_MISMATCH";
        if (a.available().compareTo(amount) < 0) return "INSUFFICIENT_FUNDS";
        a.held = a.held.add(amount);
        accounts.save(a);
        Hold h = new Hold();
        h.paymentRef = paymentRef;
        h.accountNo = accountNo;
        h.amount = amount;
        h.currency = currency;
        h.status = Hold.HELD;
        h.createdAt = h.updatedAt = Instant.now();
        holds.save(h);
        return null;
    }

    /** Releases a hold (cancel / expiry). Returns false if nothing was held. */
    @Transactional
    public synchronized boolean release(String paymentRef) {
        Hold h = holds.findById(paymentRef).filter(x -> Hold.HELD.equals(x.status)).orElse(null);
        if (h == null) return false;
        Account a = accounts.findById(h.accountNo).orElseThrow();
        a.held = a.held.subtract(h.amount).max(BigDecimal.ZERO.setScale(2));
        accounts.save(a);
        h.status = Hold.RELEASED;
        h.updatedAt = Instant.now();
        holds.save(h);
        return true;
    }

    /** Debits the customer (capturing the hold) and credits the merchant in one transaction. */
    @Transactional
    public synchronized BankTxn capture(String paymentRef, String accountNo, String merchantAccount, BigDecimal amount, String currency) {
        Optional<BankTxn> done = txns.findFirstByPaymentRefAndTypeAndStatus(paymentRef, BankTxn.PAYMENT, BankTxn.SUCCESS);
        if (done.isPresent()) return done.get();
        Hold h = holds.findById(paymentRef).filter(x -> Hold.HELD.equals(x.status)).orElse(null);
        Account debit = accounts.findById(accountNo).orElse(null);
        Account credit = accounts.findById(merchantAccount).orElse(null);
        if (debit == null || credit == null) return failed(BankTxn.PAYMENT, paymentRef, null, accountNo, merchantAccount, amount, currency, "ACCOUNT_NOT_FOUND");
        if (h != null) {
            debit.held = debit.held.subtract(h.amount).max(BigDecimal.ZERO.setScale(2));
            h.status = Hold.CAPTURED;
            h.updatedAt = Instant.now();
            holds.save(h);
        } else if (debit.available().compareTo(amount) < 0) {
            return failed(BankTxn.PAYMENT, paymentRef, null, accountNo, merchantAccount, amount, currency, "INSUFFICIENT_FUNDS");
        }
        debit.balance = debit.balance.subtract(amount);
        credit.balance = credit.balance.add(amount);
        accounts.save(debit);
        accounts.save(credit);
        return txns.save(txn(BankTxn.PAYMENT, paymentRef, null, accountNo, merchantAccount, amount, currency, BankTxn.SUCCESS, null));
    }

    /** Debits the merchant and credits the customer in one transaction; idempotent by refundId. */
    @Transactional
    public synchronized BankTxn refund(String refundId, String paymentRef, String merchantAccount, String customerAccount,
                                       BigDecimal amount, String currency) {
        Optional<BankTxn> done = txns.findFirstByRefundIdOrderByCreatedAtDesc(refundId).filter(t -> BankTxn.SUCCESS.equals(t.status));
        if (done.isPresent()) return done.get();
        Account debit = accounts.findById(merchantAccount).orElseThrow();
        Account credit = accounts.findById(customerAccount).orElseThrow();
        debit.balance = debit.balance.subtract(amount);
        credit.balance = credit.balance.add(amount);
        accounts.save(debit);
        accounts.save(credit);
        return txns.save(txn(BankTxn.REFUND, paymentRef, refundId, merchantAccount, customerAccount, amount, currency, BankTxn.SUCCESS, null));
    }

    public BankTxn failed(String type, String paymentRef, String refundId, String from, String to, BigDecimal amount, String currency, String reason) {
        return txns.save(txn(type, paymentRef, refundId, from, to, amount, currency, BankTxn.FAILED, reason));
    }

    private static BankTxn txn(String type, String paymentRef, String refundId, String from, String to,
                               BigDecimal amount, String currency, String status, String reason) {
        BankTxn t = new BankTxn();
        t.txnId = (BankTxn.REFUND.equals(type) ? "RTX-" : "BTX-") + Hmac.random(6).toUpperCase();
        t.type = type;
        t.paymentRef = paymentRef;
        t.refundId = refundId;
        t.debitAccount = from;
        t.creditAccount = to;
        t.amount = amount;
        t.currency = currency;
        t.status = status;
        t.reason = reason;
        t.createdAt = Instant.now();
        return t;
    }
}

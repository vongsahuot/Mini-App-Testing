package com.kosign.mock.bank;

import com.kosign.mock.common.ApiException;
import com.kosign.mock.common.Hmac;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/** Core banking: every money movement is one database transaction (debit + credit + journal row). */
@Component
public class Ledger {
    private final Account.Repo accounts;
    private final BankTxn.Repo txns;

    public Ledger(Account.Repo accounts, BankTxn.Repo txns) {
        this.accounts = accounts;
        this.txns = txns;
    }

    @Transactional
    public BankTxn transfer(String type, String paymentId, String refundId, String from, String to, BigDecimal amount, String currency) {
        Account debit = accounts.findById(from).orElseThrow(() -> ApiException.notFound("Account " + from));
        Account credit = accounts.findById(to).orElseThrow(() -> ApiException.notFound("Account " + to));
        debit.balance = debit.balance.subtract(amount);
        credit.balance = credit.balance.add(amount);
        accounts.save(debit);
        accounts.save(credit);
        return txns.save(txn(type, paymentId, refundId, from, to, amount, currency, BankTxn.SUCCESS, null));
    }

    public BankTxn failed(String type, String paymentId, String refundId, String from, String to, BigDecimal amount, String currency, String reason) {
        return txns.save(txn(type, paymentId, refundId, from, to, amount, currency, BankTxn.FAILED, reason));
    }

    private static BankTxn txn(String type, String paymentId, String refundId, String from, String to,
                               BigDecimal amount, String currency, String status, String reason) {
        BankTxn t = new BankTxn();
        t.txnId = (BankTxn.REFUND.equals(type) ? "RTX-" : "BTX-") + Hmac.random(6).toUpperCase();
        t.type = type;
        t.paymentId = paymentId;
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

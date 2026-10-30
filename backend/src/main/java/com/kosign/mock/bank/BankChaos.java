package com.kosign.mock.bank;

import org.springframework.stereotype.Component;

/** Fault switches for testing the recovery, failure and reconciliation paths. */
@Component
public class BankChaos {
    /** Debit succeeds but the result call to KOSIGN is never sent → payment stuck in PROCESSING → recovery. */
    public volatile boolean dropResultCallback;
    /** Core banking rejects the debit → FAILED. */
    public volatile boolean failDebit;
    /** Core banking rejects refunds → refund.failed. */
    public volatile boolean failRefund;
    /** Settlement file reports a wrong amount for the first transaction → reconciliation alert. */
    public volatile boolean settlementMismatch;
}

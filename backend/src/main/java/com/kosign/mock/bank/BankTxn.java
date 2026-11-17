package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Core Banking journal: one row per posting attempt (payment debit/credit or refund). */
@Entity
@Table(name = "transactions")
public class BankTxn {
    public static final String PAYMENT = "PAYMENT", REFUND = "REFUND", SUCCESS = "SUCCESS", FAILED = "FAILED";

    @Id public String txnId;
    public String type;
    public String paymentRef;
    public String refundId;
    public String debitAccount;
    public String creditAccount;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String status;
    public String reason;
    public Instant createdAt;

    public interface Repo extends JpaRepository<BankTxn, String> {
        Optional<BankTxn> findFirstByPaymentRefAndTypeAndStatus(String paymentRef, String type, String status);

        Optional<BankTxn> findFirstByPaymentRefAndTypeOrderByCreatedAtDesc(String paymentRef, String type);

        Optional<BankTxn> findFirstByRefundIdOrderByCreatedAtDesc(String refundId);

        List<BankTxn> findByStatusOrderByCreatedAt(String status);

        List<BankTxn> findAllByOrderByCreatedAtDesc();
    }
}

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

@Entity
@Table(name = "transactions")
public class BankTxn {
    public static final String PAYMENT = "PAYMENT", REFUND = "REFUND", SUCCESS = "SUCCESS", FAILED = "FAILED";

    @Id public String txnId;
    public String type;
    public String paymentId;
    public String refundId;
    public String debitAccount;
    public String creditAccount;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String status;
    public String reason;
    public Instant createdAt;

    public interface Repo extends JpaRepository<BankTxn, String> {
        Optional<BankTxn> findFirstByPaymentIdAndTypeOrderByCreatedAtDesc(String paymentId, String type);

        Optional<BankTxn> findFirstByPaymentIdAndTypeAndStatus(String paymentId, String type, String status);

        Optional<BankTxn> findFirstByRefundId(String refundId);

        List<BankTxn> findByStatusOrderByCreatedAt(String status);

        List<BankTxn> findAllByOrderByCreatedAtDesc();
    }
}

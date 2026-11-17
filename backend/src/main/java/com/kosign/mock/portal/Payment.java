package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A Mini App payment (txn). PENDING → INITIATED → PROCESSING → SUCCESS | FAILED; PENDING | INITIATED → EXPIRED. */
@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(columnNames = {"appId", "orderId"}))
public class Payment {
    public static final String PENDING = "PENDING", INITIATED = "INITIATED", PROCESSING = "PROCESSING", SUCCESS = "SUCCESS",
            FAILED = "FAILED", EXPIRED = "EXPIRED";

    @Id public String paymentRef;
    public String appId;
    public String clientId;
    public String type;
    public String orderId;
    public String customerId;
    public String partnerUserId;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String merchantName;
    public String description;
    public String status;
    public String failReason;
    public String bankTxnId;
    public String account;
    @Column(precision = 14, scale = 2) public BigDecimal refundedAmount = new BigDecimal("0.00");
    /** Hold release after expiry: null, PENDING (retry next run) or DONE. */
    public String cancelStatus;
    /** Set once the recovery job raised an ops alert for this payment. */
    public boolean alerted;
    public Instant expiresAt;
    public Instant processingAt;
    public Instant createdAt;
    public Instant updatedAt;

    public boolean isFinal() {
        return SUCCESS.equals(status) || FAILED.equals(status) || EXPIRED.equals(status);
    }

    public interface Repo extends JpaRepository<Payment, String> {
        Optional<Payment> findByAppIdAndOrderId(String appId, String orderId);

        List<Payment> findByClientIdAndOrderId(String clientId, String orderId);

        List<Payment> findByStatusAndProcessingAtBefore(String status, Instant before);

        List<Payment> findByStatusInAndExpiresAtBefore(Collection<String> statuses, Instant before);

        List<Payment> findByCancelStatus(String cancelStatus);

        List<Payment> findAllByOrderByCreatedAtDesc();

        /** The only way out of a state: a conditional update, so each transition happens once. */
        @Modifying
        @Query("update Payment p set p.status = :to, p.updatedAt = :now where p.paymentRef = :ref and p.status = :from")
        int transition(@Param("ref") String ref, @Param("from") String from, @Param("to") String to, @Param("now") Instant now);

        /** 3:23 PENDING → INITIATED with the account. */
        @Modifying
        @Query("update Payment p set p.status = 'INITIATED', p.account = :account, p.updatedAt = :now " +
               "where p.paymentRef = :ref and p.status = 'PENDING' and p.expiresAt > :now")
        int initiate(@Param("ref") String ref, @Param("account") String account, @Param("now") Instant now);

        /** 3:34 PROCESSING only if INITIATED and not expired (1 row updated). */
        @Modifying
        @Query("update Payment p set p.status = 'PROCESSING', p.processingAt = :now, p.updatedAt = :now " +
               "where p.paymentRef = :ref and p.status = 'INITIATED' and p.expiresAt > :now")
        int process(@Param("ref") String ref, @Param("now") Instant now);

        @Modifying
        @Query("update Payment p set p.status = :to, p.bankTxnId = :txn, p.failReason = :reason, p.updatedAt = :now " +
               "where p.paymentRef = :ref and p.status in :from")
        int finish(@Param("ref") String ref, @Param("from") Collection<String> from, @Param("to") String to,
                   @Param("txn") String bankTxnId, @Param("reason") String failReason, @Param("now") Instant now);
    }
}

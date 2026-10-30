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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(columnNames = {"clientId", "orderId"}))
public class Payment {
    public static final String PENDING = "PENDING", PROCESSING = "PROCESSING", PAID = "PAID", FAILED = "FAILED",
            CANCELLED = "CANCELLED", EXPIRED = "EXPIRED", REFUNDED = "REFUNDED", PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";

    @Id public String paymentId;
    public String orderId;
    public String clientId;
    public String partnerCustomerId;
    public String customerId;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String description;
    public String merchantName;
    public String status;
    public String bankTxnId;
    @Column(precision = 14, scale = 2) public BigDecimal refundedAmount = new BigDecimal("0.00");
    public Instant createdAt;
    public Instant expiresAt;
    public Instant lockedAt;
    public Instant updatedAt;

    public boolean settled() {
        return PAID.equals(status) || REFUNDED.equals(status) || PARTIALLY_REFUNDED.equals(status);
    }

    public interface Repo extends JpaRepository<Payment, String> {
        Optional<Payment> findByClientIdAndOrderId(String clientId, String orderId);

        List<Payment> findByStatusAndLockedAtBefore(String status, Instant before);

        List<Payment> findByStatusAndExpiresAtBefore(String status, Instant before);

        List<Payment> findAllByOrderByCreatedAtDesc();

        /** Step 36: the only way into PROCESSING, so it happens exactly once. */
        @Modifying
        @Transactional
        @Query("update Payment p set p.status = 'PROCESSING', p.lockedAt = :now, p.updatedAt = :now " +
               "where p.paymentId = :id and p.status = 'PENDING' and p.expiresAt > :now and p.customerId = :customerId")
        int lock(@Param("id") String id, @Param("customerId") String customerId, @Param("now") Instant now);

        @Modifying
        @Transactional
        @Query("update Payment p set p.status = :to, p.updatedAt = :now where p.paymentId = :id and p.status = :from")
        int transition(@Param("id") String id, @Param("from") String from, @Param("to") String to, @Param("now") Instant now);

        @Modifying
        @Transactional
        @Query("update Payment p set p.status = :to, p.bankTxnId = :txn, p.updatedAt = :now " +
               "where p.paymentId = :id and p.status = 'PROCESSING'")
        int settle(@Param("id") String id, @Param("to") String to, @Param("txn") String bankTxnId, @Param("now") Instant now);
    }
}

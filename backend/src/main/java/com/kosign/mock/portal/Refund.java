package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "refunds")
public class Refund {
    public static final String PENDING = "REFUND_PENDING", REFUNDED = "REFUNDED", FAILED = "FAILED";

    @Id public String refundId;
    public String clientId;
    public String paymentId;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String reason;
    public String status;
    public String refundTxnId;
    public Instant createdAt;
    public Instant updatedAt;

    public interface Repo extends JpaRepository<Refund, String> {
        List<Refund> findByStatusAndUpdatedAtBefore(String status, Instant before);

        List<Refund> findByPaymentId(String paymentId);

        List<Refund> findAllByOrderByCreatedAtDesc();
    }
}

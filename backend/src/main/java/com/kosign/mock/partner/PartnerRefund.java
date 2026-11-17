package com.kosign.mock.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A refund the partner asked the bank for; REFUND_PENDING ones are polled with Get Refund Status (4:13–4:16). */
@Entity
@Table(name = "refunds")
public class PartnerRefund {
    @Id public String refundId;
    public String orderId;
    public String paymentRef;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String status;
    public Instant createdAt;
    public Instant updatedAt;

    public interface Repo extends JpaRepository<PartnerRefund, String> {
        List<PartnerRefund> findByStatus(String status);

        List<PartnerRefund> findAllByOrderByCreatedAtDesc();
    }
}

package com.kosign.mock.partner;

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
@Table(name = "orders")
public class PartnerOrder {
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT", DELIVERED = "DELIVERED", FAILED = "FAILED", EXPIRED = "EXPIRED",
            REFUND_PENDING = "REFUND_PENDING", PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED", REFUNDED = "REFUNDED";

    @Id public String orderId;
    public String appId;
    public String type;
    public String itemId;
    public String title;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String partnerUserId;
    public Instant expiry;
    public String idempotencyKey;
    public String paymentRef;
    public String status;
    @Column(precision = 14, scale = 2) public BigDecimal refundedAmount = new BigDecimal("0.00");
    public String ticketCode;
    public String seat;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant lastInquiryAt;

    public interface Repo extends JpaRepository<PartnerOrder, String> {
        List<PartnerOrder> findByPartnerUserIdOrderByCreatedAtDesc(String partnerUserId);

        Optional<PartnerOrder> findByAppIdAndIdempotencyKey(String appId, String idempotencyKey);

        Optional<PartnerOrder> findByPaymentRef(String paymentRef);

        List<PartnerOrder> findByStatusAndCreatedAtBefore(String status, Instant before);

        List<PartnerOrder> findAllByOrderByCreatedAtDesc();
    }
}

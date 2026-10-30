package com.kosign.mock.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "orders")
public class PartnerOrder {
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT", PAID = "PAID", DELIVERED = "DELIVERED",
            CANCELLED = "CANCELLED", FAILED = "FAILED", REFUND_PENDING = "REFUND_PENDING", REFUNDED = "REFUNDED",
            PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";

    @Id public String orderId;
    public String itemId;
    public String title;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String partnerCustomerId;
    public String paymentId;
    public String status;
    @Column(precision = 14, scale = 2) public BigDecimal refundedAmount = new BigDecimal("0.00");
    public String ticketCode;
    public String seat;
    public Instant createdAt;
    public Instant updatedAt;

    public interface Repo extends JpaRepository<PartnerOrder, String> {
        List<PartnerOrder> findByPartnerCustomerIdOrderByCreatedAtDesc(String partnerCustomerId);

        List<PartnerOrder> findAllByOrderByCreatedAtDesc();
    }
}

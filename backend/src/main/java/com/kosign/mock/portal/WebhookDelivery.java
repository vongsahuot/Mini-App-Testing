package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {
    public static final String PENDING = "PENDING", DELIVERED = "DELIVERED", GAVE_UP = "GAVE_UP";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String eventId;
    public String type;
    public String clientId;
    public String paymentId;
    @Column(length = 8000) public String payload;
    public String status;
    public int attempts;
    public Integer lastStatusCode;
    public Instant nextAttemptAt;
    public Instant firstAttemptAt;
    public Instant createdAt;
    public Instant updatedAt;

    public interface Repo extends JpaRepository<WebhookDelivery, Long> {
        List<WebhookDelivery> findByStatusAndNextAttemptAtBeforeOrderById(String status, Instant before);

        List<WebhookDelivery> findAllByOrderByIdDesc();
    }
}

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

/** Outbox row, written in the same DB transaction as the payment's final status; the relay publishes it. */
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String eventId;
    public String paymentRef;
    public String status;
    /** Step key the relay records when it publishes (3:40, 3:38b, 3:R6, 3:R9b). */
    public String publishStep;
    @Column(length = 4000) public String payload;
    public Instant createdAt;
    public Instant publishedAt;

    public interface Repo extends JpaRepository<OutboxEvent, Long> {
        List<OutboxEvent> findByPublishedAtIsNullOrderById();

        List<OutboxEvent> findAllByOrderByIdDesc();
    }
}

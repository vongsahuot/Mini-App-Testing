package com.kosign.mock.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Webhook events already handled (dedupe by eventId) and received daily statements. */
@Entity
@Table(name = "webhook_events")
public class ProcessedEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(unique = true) public String eventId;
    public String type;
    @Column(length = 4000) public String payload;
    public Instant receivedAt;

    public interface Repo extends JpaRepository<ProcessedEvent, Long> {
        boolean existsByEventId(String eventId);

        List<ProcessedEvent> findByTypeOrderByIdDesc(String type);
    }
}

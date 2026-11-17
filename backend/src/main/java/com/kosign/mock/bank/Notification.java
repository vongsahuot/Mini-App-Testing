package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Mock push notification (C10): stored per customer, polled by the app. Also carries OTPs. */
@Entity
@Table(name = "notifications")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String customerId;
    public String kind;
    public String title;
    @Column(length = 1000) public String body;
    public String ref;
    public Instant createdAt;

    public interface Repo extends JpaRepository<Notification, Long> {
        List<Notification> findByCustomerIdAndIdGreaterThanOrderById(String customerId, Long after);

        List<Notification> findAllByOrderByIdDesc();
    }
}

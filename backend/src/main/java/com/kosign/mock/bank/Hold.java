package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Funds held by Init Payment (step 3:21), captured by Complete (3:35) or released by Cancel (R8). */
@Entity
@Table(name = "holds")
public class Hold {
    public static final String HELD = "HELD", CAPTURED = "CAPTURED", RELEASED = "RELEASED";

    @Id public String paymentRef;
    public String accountNo;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String status;
    public Instant createdAt;
    public Instant updatedAt;

    public interface Repo extends JpaRepository<Hold, String> {
        List<Hold> findAllByOrderByCreatedAtDesc();
    }
}

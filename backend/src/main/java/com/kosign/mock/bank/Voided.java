package com.kosign.mock.bank;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** A voided paymentRef or refundId ("PAYMENT:ref" / "REFUND:id"): a late complete or refund is refused (R5a, F5a). */
@Entity
@Table(name = "voids")
public class Voided {
    @Id public String id;
    public Instant at;

    public static String payment(String paymentRef) { return "PAYMENT:" + paymentRef; }

    public static String refund(String refundId) { return "REFUND:" + refundId; }

    public interface Repo extends JpaRepository<Voided, String> {}
}

package com.kosign.mock.bank;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** MB App login session; its token is the customer session token checked by the app gateway (C1). */
@Entity
@Table(name = "sessions")
public class BankSession {
    @Id public String token;
    public String customerId;
    public String deviceId;
    public Instant createdAt;

    public interface Repo extends JpaRepository<BankSession, String> {}
}

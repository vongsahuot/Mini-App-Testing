package com.kosign.mock.bank;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** Bank app login session. deviceSecret stands in for a device-bound Keystore key. */
@Entity
@Table(name = "sessions")
public class BankSession {
    @Id public String token;
    public String customerId;
    public String deviceId;
    public String deviceSecret;
    public Instant createdAt;

    public interface Repo extends JpaRepository<BankSession, String> {}
}

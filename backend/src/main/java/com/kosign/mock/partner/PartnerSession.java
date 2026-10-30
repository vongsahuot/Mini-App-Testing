package com.kosign.mock.partner;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** The partner's own login session, created after exchanging the authCode. */
@Entity
@Table(name = "sessions")
public class PartnerSession {
    @Id public String token;
    public String partnerCustomerId;
    public String name;
    public String phone;
    public Instant createdAt;

    public interface Repo extends JpaRepository<PartnerSession, String> {}
}

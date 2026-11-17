package com.kosign.mock.partner;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/**
 * The partner's own session, created after the authCode exchange (2:42). Kept as an HttpOnly cookie by the
 * WebView page, in memory by the SDK and in secure storage by the partner app. The bank never sees it.
 */
@Entity
@Table(name = "sessions")
public class PartnerSession {
    @Id public String token;
    public String appId;
    public String type;
    public String partnerUserId;
    public String name;
    public String phone;
    public Instant createdAt;

    public interface Repo extends JpaRepository<PartnerSession, String> {}
}

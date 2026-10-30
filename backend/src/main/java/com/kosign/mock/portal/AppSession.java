package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

@Entity
@Table(name = "app_sessions")
public class AppSession {
    @Id public String sessionId;
    public String appId;
    public String customerId;
    public Instant createdAt;

    public interface Repo extends JpaRepository<AppSession, String> {}
}

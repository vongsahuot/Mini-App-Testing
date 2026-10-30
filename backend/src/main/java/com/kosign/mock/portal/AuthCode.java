package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** One use, short lived, bound to clientId + customer + scopes. */
@Entity
@Table(name = "auth_codes")
public class AuthCode {
    @Id public String code;
    public String clientId;
    public String appId;
    public String customerId;
    public String scopes;
    public String customerName;
    public String customerPhone;
    public Instant expiresAt;
    public boolean used;

    public interface Repo extends JpaRepository<AuthCode, String> {
        @Modifying
        @Transactional
        @Query("update AuthCode a set a.used = true where a.code = :code and a.used = false")
        int burn(@Param("code") String code);
    }
}

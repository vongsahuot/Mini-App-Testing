package com.kosign.mock.partner;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The partner's own user, created by the bank's Register User call (2:16). Same bankUserRef → same user. */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = {"appId", "bankUserRef"}))
public class PartnerUser {
    @Id public String partnerUserId;
    public String appId;
    public String bankUserRef;
    public String name;
    public String phone;
    public Instant createdAt;

    public interface Repo extends JpaRepository<PartnerUser, String> {
        Optional<PartnerUser> findByAppIdAndBankUserRef(String appId, String bankUserRef);

        List<PartnerUser> findAllByOrderByCreatedAtDesc();
    }
}

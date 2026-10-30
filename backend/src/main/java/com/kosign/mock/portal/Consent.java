package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** Scopes a customer has allowed for one mini app (the consent sheet is shown only the first time). */
@Entity
@Table(name = "consents")
public class Consent {
    @Id public String id; // customerId:appId
    public String customerId;
    public String appId;
    public String scopes; // comma separated
    public Instant grantedAt;

    public interface Repo extends JpaRepository<Consent, String> {}
}

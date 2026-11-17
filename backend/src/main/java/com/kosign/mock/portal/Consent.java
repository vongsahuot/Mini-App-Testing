package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/** The consentVersion a customer accepted for one Mini App, with the profile snapshot that may be shared. */
@Entity
@Table(name = "consents")
public class Consent {
    @Id public String id; // customerId:appId
    public String customerId;
    public String appId;
    public String version;
    public String scopes;
    public String name;
    public String phone;
    public Instant acceptedAt;

    public static String id(String customerId, String appId) {
        return customerId + ":" + appId;
    }

    public interface Repo extends JpaRepository<Consent, String> {}
}

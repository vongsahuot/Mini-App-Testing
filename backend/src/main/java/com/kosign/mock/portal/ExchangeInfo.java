package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** User exchange info (2:19): which partnerUserId the partner gave this customer for this Mini App. */
@Entity
@Table(name = "exchange_info")
public class ExchangeInfo {
    @Id public String id; // customerId:appId
    public String customerId;
    public String appId;
    public String clientId;
    public String partnerUserId;
    /** Pairwise customer reference sent to the partner instead of the bank's customer id. */
    public String bankUserRef;
    public Instant createdAt;

    public interface Repo extends JpaRepository<ExchangeInfo, String> {
        Optional<ExchangeInfo> findFirstByAppIdAndPartnerUserId(String appId, String partnerUserId);

        List<ExchangeInfo> findAllByOrderByCreatedAtDesc();
    }
}

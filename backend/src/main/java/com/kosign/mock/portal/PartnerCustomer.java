package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

/** Pairwise partnerCustomerId → bank customerId (the partner never sees the bank's id). */
@Entity
@Table(name = "partner_customers")
public class PartnerCustomer {
    @Id public String partnerCustomerId;
    public String clientId;
    public String customerId;

    public interface Repo extends JpaRepository<PartnerCustomer, String> {}
}

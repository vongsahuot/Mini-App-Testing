package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@Entity
@Table(name = "customers")
public class Customer {
    @Id public String customerId;
    @Column(unique = true) public String username;
    public String pin;
    public String name;
    public String phone;
    /** Used by the Mini App eligibility rules (PREMIUM / MASS). */
    public String segment;
    /** Wrong PIN / OTP entries in a row; payment authentication locks at the configured maximum. */
    public int authFailures;
    public boolean authLocked;

    public interface Repo extends JpaRepository<Customer, String> {
        Optional<Customer> findByUsername(String username);
    }
}

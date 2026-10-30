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

    public interface Repo extends JpaRepository<Customer, String> {
        Optional<Customer> findByUsername(String username);
    }
}

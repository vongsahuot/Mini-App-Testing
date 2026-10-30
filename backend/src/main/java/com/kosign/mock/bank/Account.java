package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "accounts")
public class Account {
    @Id public String accountNo;
    /** Null for the bank's own settlement (escrow) account. */
    public String customerId;
    public String name;
    @Column(precision = 14, scale = 2) public BigDecimal balance;
    public String currency;

    public interface Repo extends JpaRepository<Account, String> {
        List<Account> findByCustomerIdOrderByAccountNo(String customerId);

        List<Account> findAllByOrderByAccountNo();
    }
}

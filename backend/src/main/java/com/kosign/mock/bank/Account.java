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
    /** Null for the merchant settlement account. */
    public String customerId;
    public String name;
    @Column(precision = 14, scale = 2) public BigDecimal balance;
    /** Funds on hold by Init Payment, not yet debited. */
    @Column(precision = 14, scale = 2) public BigDecimal held = new BigDecimal("0.00");
    public String currency;

    public BigDecimal available() {
        return balance.subtract(held);
    }

    public interface Repo extends JpaRepository<Account, String> {
        List<Account> findByCustomerIdOrderByAccountNo(String customerId);

        List<Account> findAllByOrderByAccountNo();
    }
}

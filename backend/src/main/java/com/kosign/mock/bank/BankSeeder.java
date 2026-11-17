package com.kosign.mock.bank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Seeds test customers once; balances then persist across restarts (reset with the console or by dropping the volume). */
@Component
public class BankSeeder implements ApplicationRunner {
    private final Customer.Repo customers;
    private final Account.Repo accounts;
    private final String merchantAccount;

    public BankSeeder(Customer.Repo customers, Account.Repo accounts, @Value("${mock.merchant-account}") String merchantAccount) {
        this.customers = customers;
        this.accounts = accounts;
        this.merchantAccount = merchantAccount;
    }

    @Override
    public void run(ApplicationArguments args) {
        customer("C1001", "alice", "Alice Sok", "+855 12 345 678", "PREMIUM", "001-100-1001", "500.00");
        customer("C1002", "bob", "Bob Chan", "+855 98 765 432", "MASS", "001-100-2001", "3.00");
        if (!accounts.existsById(merchantAccount)) accounts.save(account(merchantAccount, null, "Starlight Cinema settlement", "0.00"));
    }

    private void customer(String id, String username, String name, String phone, String segment, String accountNo, String balance) {
        if (customers.existsById(id)) return;
        Customer c = new Customer();
        c.customerId = id;
        c.username = username;
        c.pin = "1234";
        c.name = name;
        c.phone = phone;
        c.segment = segment;
        customers.save(c);
        accounts.save(account(accountNo, id, "Savings", balance));
    }

    private static Account account(String no, String customerId, String name, String balance) {
        Account a = new Account();
        a.accountNo = no;
        a.customerId = customerId;
        a.name = name;
        a.balance = new BigDecimal(balance);
        a.held = new BigDecimal("0.00");
        a.currency = "USD";
        return a;
    }
}

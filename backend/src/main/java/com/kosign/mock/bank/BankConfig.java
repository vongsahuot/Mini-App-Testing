package com.kosign.mock.bank;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Bank server role (lane BS): bank app backend, core banking ledger and the bank side of KOSIGN. */
@Configuration
@Profile("bank")
@ComponentScan("com.kosign.mock.bank")
@EntityScan("com.kosign.mock.bank")
@EnableJpaRepositories(basePackages = "com.kosign.mock.bank", considerNestedRepositories = true)
public class BankConfig {}

package com.kosign.mock.bank;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Bank role: the app side of the Public Gateway (PG), Mobile Backend (MBE), Local Gateway (LG)
 * and Core Banking (CB).
 */
@Configuration
@Profile("bank")
@ComponentScan("com.kosign.mock.bank")
@EntityScan("com.kosign.mock.bank")
@EnableJpaRepositories(basePackages = "com.kosign.mock.bank", considerNestedRepositories = true)
public class BankConfig {}

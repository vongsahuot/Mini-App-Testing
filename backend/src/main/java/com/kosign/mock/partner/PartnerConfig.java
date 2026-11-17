package com.kosign.mock.partner;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Partner server role (lane PS): the "Starlight Cinema" partner's backend for all four integration types. */
@Configuration
@Profile("partner")
@ComponentScan("com.kosign.mock.partner")
@EntityScan("com.kosign.mock.partner")
@EnableJpaRepositories(basePackages = "com.kosign.mock.partner", considerNestedRepositories = true)
public class PartnerConfig {}

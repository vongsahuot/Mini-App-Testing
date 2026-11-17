package com.kosign.mock.portal;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Portal role: Mini App Service (MAS), the partner side of the Public Gateway (PG) and the Kafka mock (KF). */
@Configuration
@Profile("portal")
@ComponentScan("com.kosign.mock.portal")
@EntityScan("com.kosign.mock.portal")
@EnableJpaRepositories(basePackages = "com.kosign.mock.portal", considerNestedRepositories = true)
public class PortalConfig {}

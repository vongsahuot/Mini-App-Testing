package com.kosign.mock.portal;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** KOSIGN Portal role (lane KP). */
@Configuration
@Profile("portal")
@ComponentScan("com.kosign.mock.portal")
@EntityScan("com.kosign.mock.portal")
@EnableJpaRepositories(basePackages = "com.kosign.mock.portal", considerNestedRepositories = true)
public class PortalConfig {}

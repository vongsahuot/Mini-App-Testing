package com.kosign.mock;

import com.kosign.mock.bank.BankConfig;
import com.kosign.mock.partner.PartnerConfig;
import com.kosign.mock.portal.PortalConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One jar, three roles. The active Spring profile (portal | bank | partner) decides which
 * role's config is imported; each role config scans only its own package, entities and repositories.
 */
@SpringBootApplication(scanBasePackages = "com.kosign.mock.common")
@Import({PortalConfig.class, BankConfig.class, PartnerConfig.class})
@EnableScheduling
public class MockApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}

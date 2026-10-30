package com.kosign.mock.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Partners and the mini apps list are re-seeded on every start so config changes apply. */
@Component
public class PortalSeeder implements ApplicationRunner {
    private final Partner.Repo partners;
    private final MiniApp.Repo apps;
    private final String entryUrl, allowedOrigins, webhookUrl, secret;

    public PortalSeeder(Partner.Repo partners, MiniApp.Repo apps,
                        @Value("${mock.seed.partner-entry-url}") String entryUrl,
                        @Value("${mock.seed.partner-allowed-origins}") String allowedOrigins,
                        @Value("${mock.seed.partner-webhook-url}") String webhookUrl,
                        @Value("${mock.seed.partner-secret}") String secret) {
        this.partners = partners;
        this.apps = apps;
        this.entryUrl = entryUrl;
        this.allowedOrigins = allowedOrigins;
        this.webhookUrl = webhookUrl;
        this.secret = secret;
    }

    @Override
    public void run(ApplicationArguments args) {
        Partner p = new Partner();
        p.clientId = "movie-tickets";
        p.name = "Movie tickets";
        p.secret = secret;
        p.webhookUrl = webhookUrl;
        p.entryUrl = entryUrl;
        p.allowedOrigins = allowedOrigins;
        p.settlementAccount = "999-ESCROW-01";
        partners.save(p);

        apps.save(app("movie-tickets", "Movie tickets", "🎬", null, true, "featured", "Featured", 1, "movie-tickets"));
        apps.save(app("coffee", "Coffee", "☕", "Soon", false, "featured", "Featured", 2, null));
        apps.save(app("taxi", "Taxi", "🚕", "Soon", false, "lifestyle", "Lifestyle", 3, null));
    }

    private static MiniApp app(String id, String name, String icon, String badge, boolean enabled,
                               String sectionId, String sectionTitle, int order, String clientId) {
        MiniApp a = new MiniApp();
        a.appId = id;
        a.name = name;
        a.icon = icon;
        a.badge = badge;
        a.enabled = enabled;
        a.sectionId = sectionId;
        a.sectionTitle = sectionTitle;
        a.sortOrder = order;
        a.clientId = clientId;
        return a;
    }
}

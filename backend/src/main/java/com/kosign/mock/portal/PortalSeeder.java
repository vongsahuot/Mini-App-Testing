package com.kosign.mock.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** The partner registry and Mini Apps (docs/CONTRACT.md, "Seed data"), re-seeded on every start and on reset. */
@Component
public class PortalSeeder implements ApplicationRunner {
    private final Partner.Repo partners;
    private final MiniApp.Repo apps;
    private final String partnerUrl, webUrl, partnerPublicKey, partnerCert, orderKey;

    public PortalSeeder(Partner.Repo partners, MiniApp.Repo apps,
                        @Value("${mock.partner-url}") String partnerUrl, @Value("${mock.web-url}") String webUrl,
                        @Value("${mock.partner-public-key}") String partnerPublicKey, @Value("${mock.partner-cert}") String partnerCert,
                        @Value("${mock.partner-order-key}") String orderKey) {
        this.partners = partners;
        this.apps = apps;
        this.partnerUrl = partnerUrl;
        this.webUrl = webUrl;
        this.partnerPublicKey = partnerPublicKey;
        this.partnerCert = partnerCert;
        this.orderKey = orderKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        Partner p = new Partner();
        p.clientId = "starlight";
        p.name = "Starlight Cinema";
        p.allowedIps = "127.0.0.1/32, ::1/128, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16";
        p.certFingerprint = partnerCert;
        p.publicKey = partnerPublicKey;
        p.orderKey = orderKey;
        p.registerUserUrl = partnerUrl + "/bank/v1/users/register";
        p.notifyUrl = partnerUrl + "/bank/v1/payments/notify";
        p.apiBaseUrl = partnerUrl + "/bank/v1/operations";
        p.createOrderUrl = partnerUrl + "/bank/v1/orders";
        p.operations = "catalog, orders";
        p.merchantAccount = "999-STARLIGHT-01";
        partners.save(p);

        MiniApp web = app("starlight-web", "Cinema · Web", "🎬", MiniApp.WEBVIEW, true, 1);
        web.entryUrl = webUrl + "/partner/movie-tickets";
        web.allowedOrigins = webUrl;
        apps.save(web);

        apps.save(app("starlight-native", "Cinema · Native", "🎟️", MiniApp.API, true, 2));

        MiniApp sdk = app("starlight-sdk", "Cinema · SDK", "🍿", MiniApp.SDK, true, 3);
        sdk.sdkModule = "starlight-sdk";
        sdk.minSdkVersion = "2.0.0";
        apps.save(sdk);

        MiniApp a2a = app("starlight-app", "Cinema · App", "📱", MiniApp.A2A, true, 4);
        a2a.appLink = webUrl + "/links/starlight/open";
        a2a.returnLink = webUrl + "/links/starlight/return";
        a2a.storeUrl = webUrl + "/store/starlight";
        apps.save(a2a);

        MiniApp vip = app("vip-screening", "VIP screening", "⭐", MiniApp.WEBVIEW, true, 5);
        vip.sectionId = "premium";
        vip.sectionTitle = "For Premium customers";
        vip.segments = "PREMIUM";
        vip.entryUrl = webUrl + "/partner/movie-tickets?room=vip";
        vip.allowedOrigins = webUrl;
        apps.save(vip);

        MiniApp coffee = app("coffee", "Coffee", "☕", MiniApp.WEBVIEW, false, 6);
        coffee.sectionId = "lifestyle";
        coffee.sectionTitle = "Lifestyle";
        apps.save(coffee);

        MiniApp taxi = app("taxi", "Taxi", "🚕", MiniApp.WEBVIEW, true, 7);
        taxi.sectionId = "lifestyle";
        taxi.sectionTitle = "Lifestyle";
        taxi.minAppVersion = "9.0.0";
        apps.save(taxi);
    }

    private static MiniApp app(String id, String name, String icon, String type, boolean enabled, int order) {
        MiniApp a = new MiniApp();
        a.appId = id;
        a.clientId = "starlight";
        a.name = name;
        a.icon = icon;
        a.type = type;
        a.enabled = enabled;
        a.sectionId = "entertainment";
        a.sectionTitle = "Entertainment";
        a.sortOrder = order;
        a.consentVersion = "1";
        a.scopes = "name, phone";
        a.registrationRequired = true;
        a.sdkEnabled = true;
        return a;
    }
}

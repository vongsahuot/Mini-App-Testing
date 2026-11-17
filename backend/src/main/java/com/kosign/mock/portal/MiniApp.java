package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Entity
@Table(name = "mini_apps")
public class MiniApp {
    public static final String WEBVIEW = "webview", API = "api", SDK = "sdk", A2A = "a2a";

    @Id public String appId;
    public String clientId;
    public String name;
    public String icon;
    public String type;
    public boolean enabled;
    public String sectionId;
    public String sectionTitle;
    public int sortOrder;
    /** Eligibility rules: lowest MB App version, and the customer segments allowed (null = all). */
    public String minAppVersion;
    public String segments;
    public String consentVersion;
    /** Comma separated data scopes the customer consents to share (name, phone, email). */
    public String scopes;
    public boolean registrationRequired;
    // webview
    public String entryUrl;
    public String allowedOrigins;
    // sdk
    public String sdkModule;
    public String minSdkVersion;
    public boolean sdkEnabled = true;
    // a2a
    public String appLink;
    public String returnLink;
    public String storeUrl;

    public List<String> scopeList() {
        return Partner.split(scopes);
    }

    public interface Repo extends JpaRepository<MiniApp, String> {
        List<MiniApp> findAllByOrderBySortOrder();
    }
}

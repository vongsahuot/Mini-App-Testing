package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Arrays;
import java.util.List;

/** Partner registry, filled at onboarding through Mini App Admin. */
@Entity
@Table(name = "partners")
public class Partner {
    @Id public String clientId;
    public String name;
    /** Comma separated CIDRs the partner server may call from. */
    @Column(length = 1000) public String allowedIps;
    /** Mock mTLS: fingerprint of the partner's client certificate. */
    public String certFingerprint;
    /** ES256 public key (X.509 SPKI, base64). */
    @Column(length = 1000) public String publicKey;
    /** HMAC order key. */
    public String orderKey;
    public String registerUserUrl;
    public String notifyUrl;
    public String apiBaseUrl;
    public String createOrderUrl;
    /** Comma separated partner operations Mini App Service may call (API linkage). */
    public String operations;
    public String merchantAccount;

    public List<String> ips() {
        return split(allowedIps);
    }

    public List<String> operationList() {
        return split(operations);
    }

    static List<String> split(String s) {
        return s == null ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public interface Repo extends JpaRepository<Partner, String> {}
}

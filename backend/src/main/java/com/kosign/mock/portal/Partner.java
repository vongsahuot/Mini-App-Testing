package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "partners")
public class Partner {
    @Id public String clientId;
    public String name;
    public String secret;
    public String webhookUrl;
    public String entryUrl;
    /** Comma separated origins (scheme://host[:port]). */
    public String allowedOrigins;
    public String settlementAccount;

    public List<String> origins() {
        return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public interface Repo extends JpaRepository<Partner, String> {}
}

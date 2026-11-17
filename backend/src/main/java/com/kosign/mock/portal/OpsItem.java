package com.kosign.mock.portal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** An open item for bank ops (alerts from recovery, dead letters, reconciliation mismatches). */
@Entity
@Table(name = "ops_items")
public class OpsItem {
    public static final String OPEN = "OPEN", RESOLVED = "RESOLVED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String kind;
    public String ref;
    @Column(length = 1000) public String message;
    public String status;
    public String resolvedBy;
    @Column(length = 1000) public String resolution;
    public Instant createdAt;
    public Instant resolvedAt;

    public interface Repo extends JpaRepository<OpsItem, Long> {
        List<OpsItem> findAllByOrderByIdDesc();
    }
}

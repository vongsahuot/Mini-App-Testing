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

/** Audit log: refund outcomes and every Mini App Admin action (who, what, when). */
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String actor;
    public String action;
    public String ref;
    @Column(length = 1000) public String detail;
    public Instant at;

    public interface Repo extends JpaRepository<AuditLog, Long> {
        List<AuditLog> findAllByOrderByIdDesc();
    }
}

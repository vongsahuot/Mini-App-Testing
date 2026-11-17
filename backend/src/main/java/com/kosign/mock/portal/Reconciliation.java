package com.kosign.mock.portal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Daily reconciliation report (4:19). */
@Entity
@Table(name = "reconciliations")
public class Reconciliation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String date;
    public int entries;
    public int matched;
    public int fixed;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reconciliation_mismatches")
    @Column(length = 1000)
    public List<String> mismatches = new ArrayList<>();
    public Instant createdAt;

    public interface Repo extends JpaRepository<Reconciliation, Long> {
        List<Reconciliation> findAllByOrderByIdDesc();
    }
}

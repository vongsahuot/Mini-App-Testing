package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** ShedLock-style lock row: a scheduled job runs only on the node whose conditional update succeeds. */
@Entity
@Table(name = "job_locks")
public class JobLock {
    @Id public String name;
    public Instant lockedUntil;
    public String lockedBy;

    public interface Repo extends JpaRepository<JobLock, String> {
        @Modifying
        @Transactional
        @Query("update JobLock j set j.lockedUntil = :until, j.lockedBy = :by where j.name = :name and j.lockedUntil < :now")
        int acquire(@Param("name") String name, @Param("until") Instant until, @Param("by") String by, @Param("now") Instant now);

        @Modifying
        @Transactional
        @Query("update JobLock j set j.lockedUntil = :now where j.name = :name and j.lockedBy = :by")
        int release(@Param("name") String name, @Param("by") String by, @Param("now") Instant now);
    }
}

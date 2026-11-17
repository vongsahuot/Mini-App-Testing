package com.kosign.mock.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Ops alerts (open items for the ops view), the audit log, and ShedLock-style job locks. */
@Component
public class Ops {
    private static final Logger log = LoggerFactory.getLogger(Ops.class);
    private static final String NODE = "portal-" + UUID.randomUUID().toString().substring(0, 8);

    private final OpsItem.Repo items;
    private final AuditLog.Repo audit;
    private final JobLock.Repo locks;

    public Ops(OpsItem.Repo items, AuditLog.Repo audit, JobLock.Repo locks) {
        this.items = items;
        this.audit = audit;
        this.locks = locks;
    }

    public OpsItem alert(String kind, String ref, String message) {
        log.warn("ALERT ops [{}] {}: {}", kind, ref, message);
        OpsItem i = new OpsItem();
        i.kind = kind;
        i.ref = ref;
        i.message = message;
        i.status = OpsItem.OPEN;
        i.createdAt = Instant.now();
        return items.save(i);
    }

    public void audit(String actor, String action, String ref, String detail) {
        AuditLog a = new AuditLog();
        a.actor = actor;
        a.action = action;
        a.ref = ref;
        a.detail = detail;
        a.at = Instant.now();
        audit.save(a);
    }

    /** Runs the job only if this node gets the lock row (one node at a time). */
    public boolean runLocked(String name, Duration maxRun, Runnable job) {
        Instant now = Instant.now();
        if (!locks.existsById(name)) {
            JobLock l = new JobLock();
            l.name = name;
            l.lockedUntil = Instant.EPOCH;
            try {
                locks.saveAndFlush(l);
            } catch (RuntimeException ignored) {
                // another node created it first
            }
        }
        if (locks.acquire(name, now.plus(maxRun), NODE, now) == 0) return false;
        try {
            job.run();
        } finally {
            locks.release(name, NODE, Instant.now());
        }
        return true;
    }
}

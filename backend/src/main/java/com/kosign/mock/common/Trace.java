package com.kosign.mock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory flow trace. Each step of the sequence diagram is recorded by its receiver
 * (see docs/CONTRACT.md). Step 0 marks an un-numbered note (retries, chaos, rejections).
 */
@Component
public class Trace {
    private static final Logger log = LoggerFactory.getLogger(Trace.class);
    private static final int MAX = 3000;

    public record Event(long id, Instant ts, String actor, int step, String title, String detail, String ref) {}

    private final Deque<Event> events = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final String actor;

    public Trace(@Value("${mock.actor:??}") String actor) {
        this.actor = actor;
    }

    public void record(int step, String title, String detail, String ref) {
        record(actor, step, title, detail, ref);
    }

    public synchronized void record(String actor, int step, String title, String detail, String ref) {
        Event e = new Event(seq.incrementAndGet(), Instant.now(), actor, step, title, detail, ref);
        events.addLast(e);
        if (events.size() > MAX) events.removeFirst();
        log.info("[{}] step {} {}{}{}", actor, step == 0 ? "-" : step, title,
                detail == null ? "" : " | " + detail, ref == null ? "" : " (" + ref + ")");
    }

    public synchronized List<Event> list() {
        return new ArrayList<>(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}

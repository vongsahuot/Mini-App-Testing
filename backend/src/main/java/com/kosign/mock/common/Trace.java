package com.kosign.mock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory flow trace. Each step of the sequence diagram is recorded by its receiver lane
 * (docs/CONTRACT.md, "Trace"). Step keys are "section:n" (e.g. "3:16", "2:A5"); null marks an
 * un-numbered note (retry, chaos, rejection). flow is the integration type when the step is type specific.
 */
@Component
public class Trace {
    private static final Logger log = LoggerFactory.getLogger(Trace.class);
    private static final int MAX = 5000;

    public record Event(long id, Instant ts, String actor, String step, String flow, String title, String detail, String ref) {}

    private final Deque<Event> events = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public synchronized void record(String actor, String step, String flow, String title, String detail, String ref) {
        Event e = new Event(seq.incrementAndGet(), Instant.now(), actor, step, flow, title, detail, ref);
        events.addLast(e);
        if (events.size() > MAX) events.removeFirst();
        log.info("[{}] {} {}{}{}{}", actor, step == null ? "-" : step, title, detail == null ? "" : " | " + detail,
                ref == null ? "" : " (" + ref + ")", flow == null ? "" : " [" + flow + "]");
    }

    /** Un-numbered note (retry, chaos, rejection). */
    public void note(String actor, String title, String detail, String ref) {
        record(actor, null, null, title, detail, ref);
    }

    public synchronized List<Event> list() {
        return new ArrayList<>(events);
    }

    public synchronized void clear() {
        events.clear();
    }
}

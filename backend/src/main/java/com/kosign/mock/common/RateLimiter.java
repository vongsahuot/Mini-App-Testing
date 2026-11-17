package com.kosign.mock.common;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed one-second window per key, used by both gateways. 429 RATE_LIMITED when exceeded. */
@Component
public class RateLimiter {
    private static final int PER_SECOND = 30;

    private record Window(long second, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String key) {
        long second = System.currentTimeMillis() / 1000;
        Window w = windows.compute(key, (k, old) -> old == null || old.second != second ? new Window(second, 1) : new Window(second, old.count + 1));
        if (w.count > PER_SECOND) throw new ApiException(429, "RATE_LIMITED", "Too many requests, slow down");
        if (windows.size() > 10_000) windows.values().removeIf(x -> x.second < second - 5);
    }
}

package com.noveltea.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * An in-memory sliding window.
 *
 * <p>Deliberately not distributed. A self-hosted install is one process, and a shared
 * store would be another service to run for a feature that only needs to slow down
 * guessing. <b>Behind more than one instance this limits per instance</b>, which is a real
 * limitation rather than a subtlety — note it before scaling out.
 */
@Component
public class SlidingWindowLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** @return true when the caller is within its allowance, false when it is not */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);

        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /** Called after a success, so a legitimate user is not punished for earlier typos. */
    public void reset(String key) {
        hits.remove(key);
    }

    /** Drops windows nothing has touched, so an abandoned key is not a slow leak. */
    public int evictIdle(Duration window) {
        Instant cutoff = Instant.now().minus(window.multipliedBy(2));
        int before = hits.size();
        hits.entrySet().removeIf(entry -> {
            Deque<Instant> timestamps = entry.getValue();
            synchronized (timestamps) {
                return timestamps.isEmpty() || timestamps.peekLast().isBefore(cutoff);
            }
        });
        return before - hits.size();
    }

    int trackedKeys() {
        return hits.size();
    }
}

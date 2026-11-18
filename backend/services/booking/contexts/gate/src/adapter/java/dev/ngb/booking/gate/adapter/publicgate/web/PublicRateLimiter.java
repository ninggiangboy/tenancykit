package dev.ngb.booking.gate.adapter.publicgate.web;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-minute token bucket per fingerprint, held in this instance's memory.
 *
 * <p>The limit is therefore per instance, so a deployment with N replicas allows N times the setting; that is documented
 * rather than hidden. The map is pruned when it grows, because a month of unique visitors must not become a leak.
 */
public final class PublicRateLimiter {

    private static final int MAX_FINGERPRINTS = 50_000;

    private final int limitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PublicRateLimiter(int limitPerMinute) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("the public rate limit must be at least 1");
        }
        this.limitPerMinute = limitPerMinute;
    }

    /** @return the seconds to wait, or 0 when the request is allowed */
    public int allowed(String fingerprint, Instant now) {
        long minute = now.getEpochSecond() / 60;
        if (windows.size() > MAX_FINGERPRINTS) {
            windows.clear();
        }
        Window window = windows.compute(fingerprint, (key, current) -> {
            if (current == null || current.minute != minute) {
                return new Window(minute, new AtomicInteger(1));
            }
            current.count.incrementAndGet();
            return current;
        });
        int used = window.count.get();
        if (used <= limitPerMinute) {
            return 0;
        }
        long secondsIntoMinute = now.getEpochSecond() % 60;
        return (int) (60 - secondsIntoMinute);
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger count;

        private Window(long minute, AtomicInteger count) {
            this.minute = minute;
            this.count = count;
        }
    }
}

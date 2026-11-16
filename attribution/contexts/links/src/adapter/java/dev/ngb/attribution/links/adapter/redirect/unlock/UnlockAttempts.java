package dev.ngb.attribution.links.adapter.redirect.unlock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Refuses repeated wrong passwords for one link and one visitor hash, before BCrypt runs.
 *
 * <p>A password check is deliberately slow, so an unlimited public unlock endpoint is a way to spend a server's CPU.
 * The counter is in memory and per instance, like the public rate limits; the window resets after the configured time
 * so a legitimate visitor who mistyped twice is not locked out for long.
 */
public class UnlockAttempts {

    private final Cache<String, AtomicInteger> failures;
    private final int limit;

    /**
     * @param limit how many failures one link and visitor hash may accumulate in the window
     * @param window how long the failures are remembered
     */
    public UnlockAttempts(int limit, Duration window) {
        if (limit < 1) {
            throw new IllegalArgumentException("an unlock limit must be positive");
        }
        this.limit = limit;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(1_000_000)
                .build();
    }

    /** Whether this link and visitor may still attempt a password. */
    public boolean allowed(String linkId, String visitorHash) {
        AtomicInteger count = failures.getIfPresent(key(linkId, visitorHash));
        return count == null || count.get() < limit;
    }

    /** Record a wrong password, so the next attempt counts against the limit. */
    public void recordFailure(String linkId, String visitorHash) {
        failures.get(key(linkId, visitorHash), ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** A successful unlock clears the failures, so a person who remembered the password is not left limited. */
    public void clear(String linkId, String visitorHash) {
        failures.invalidate(key(linkId, visitorHash));
    }

    private static String key(String linkId, String visitorHash) {
        return linkId + "/" + visitorHash;
    }
}

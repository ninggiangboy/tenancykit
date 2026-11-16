package dev.ngb.attribution.links.adapter.redirect.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/**
 * In-memory token buckets for the public paths, keyed by a visitor hash or a link.
 *
 * <p>The buckets live in this process: with N instances the effective limit is N times the setting, which is documented
 * rather than fixed with a shared store. A database check on the redirect path would cost the latency the target exists
 * to protect, and a public limit is an abuse brake, not a quota.
 *
 * <p>A bucket refills continuously and holds at most one minute's worth, so a brand-new key may burst once and then
 * sustains the configured rate.
 */
public class PublicRateLimiter {

    private final Cache<String, Bucket> buckets;
    private final double perMinute;

    /**
     * @param perMinute how many requests one key may make per minute
     * @param idleExpiry how long an unused bucket is kept before it is dropped, so the map stays bounded
     */
    public PublicRateLimiter(long perMinute, Duration idleExpiry) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("a public rate limit must be positive");
        }
        this.perMinute = perMinute;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(idleExpiry)
                .maximumSize(1_000_000)
                .build();
    }

    /**
     * Take one token.
     *
     * @param key what is being limited, such as {@code "v:" + visitorHash} or {@code "l:" + linkId}
     * @return the whole seconds to wait before retrying, or zero when the request may proceed
     */
    public long acquire(String key) {
        Bucket bucket = buckets.get(key, ignored -> new Bucket(perMinute));
        return bucket.take();
    }

    /** One token bucket: fractional tokens, refilled continuously, never above the capacity. */
    private static final class Bucket {

        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long refilledAt;

        Bucket(double perMinute) {
            this.capacity = perMinute;
            this.tokensPerNano = perMinute / Duration.ofMinutes(1).toNanos();
            this.tokens = perMinute;
            this.refilledAt = System.nanoTime();
        }

        synchronized long take() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - refilledAt) * tokensPerNano);
            refilledAt = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            double missing = 1.0 - tokens;
            return (long) Math.ceil(missing / (tokensPerNano * 1_000_000_000.0));
        }
    }
}

package dev.ngb.survey.collection.application.guard.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-instance token bucket for the public surface, keyed by an opaque caller fingerprint.
 *
 * <p>The key is never an address: the web edge builds it from an HMAC of the client IP with a daily-rotated secret, so
 * the memory of this map and any log line cannot identify a person. The map is size-bounded and drops the least
 * recently used bucket first, because a public endpoint must not leak memory under a spray of keys.
 *
 * <p>With N instances the effective limit is N times the setting. That is documented rather than solved: a shared
 * store would add a dependency this app does not otherwise need, and the limit exists to blunt a script, not to
 * account.
 */
public class PublicRateLimiter {

    /** Buckets kept before the oldest is dropped; one entry per active caller fingerprint. */
    private static final int MAX_BUCKETS = 50_000;

    /** One caller's bucket: the tokens it holds and when the bucket was last refilled. */
    private record Bucket(double tokens, Instant refilledAt) {}

    private final int tokensPerMinute;
    private final LongSupplier clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param tokensPerMinute one caller's allowance
     * @param clock the epoch-millisecond clock, injected so a test does not sleep
     */
    public PublicRateLimiter(int tokensPerMinute, LongSupplier clock) {
        this.tokensPerMinute = tokensPerMinute;
        this.clock = clock;
    }

    /**
     * Takes one token for this caller.
     *
     * @return the seconds to wait when the allowance is spent, or empty when the call may proceed
     */
    public java.util.OptionalLong refuse(String fingerprint) {
        if (tokensPerMinute <= 0) {
            return java.util.OptionalLong.empty();
        }
        Instant now = Instant.ofEpochMilli(clock.getAsLong());
        if (buckets.size() > MAX_BUCKETS) {
            buckets.clear();
        }
        Bucket bucket = buckets.compute(fingerprint, (key, current) -> refill(current, now));
        if (bucket.tokens() < 1) {
            double deficit = 1 - bucket.tokens();
            long seconds = (long) Math.ceil(deficit * 60 / tokensPerMinute);
            return java.util.OptionalLong.of(Math.max(1, seconds));
        }
        buckets.put(fingerprint, new Bucket(bucket.tokens() - 1, bucket.refilledAt()));
        return java.util.OptionalLong.empty();
    }

    private Bucket refill(Bucket current, Instant now) {
        if (current == null) {
            return new Bucket(tokensPerMinute, now);
        }
        double elapsedMinutes = (now.toEpochMilli() - current.refilledAt().toEpochMilli()) / 60_000.0;
        double refilled = current.tokens() + elapsedMinutes * tokensPerMinute;
        return new Bucket(Math.min(tokensPerMinute, refilled), now);
    }
}

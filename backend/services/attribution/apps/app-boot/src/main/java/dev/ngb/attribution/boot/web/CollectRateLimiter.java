package dev.ngb.attribution.boot.web;

import dev.ngb.attribution.links.adapter.redirect.ratelimit.PublicRateLimiter;
import java.time.Duration;

/**
 * The collection endpoint's per-tenant rate, a distinct type from the redirect's so a bean of the shared limiter cannot
 * be injected into the wrong controller.
 *
 * <p>It is a separate bucket from the redirect's on purpose: a page that fires many events must not spend the visitor's
 * budget for following links, and the two paths have different natural limits.
 */
public class CollectRateLimiter {

    private final PublicRateLimiter delegate;

    public CollectRateLimiter(long perMinute, Duration idleExpiry) {
        this.delegate = new PublicRateLimiter(perMinute, idleExpiry);
    }

    /** @return the seconds to wait before retrying, or zero when the request may proceed */
    public long acquire(String key) {
        return delegate.acquire(key);
    }
}

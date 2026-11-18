package dev.ngb.platform.integration.application.auth.port;

/** Admits or rejects one token request for a key. Every instance shares the same store. */
public interface RateLimiter {

    boolean tryAcquire(String key);

    /**
     * Admits against a bucket with its own allowance. A budget shared by every caller that did not authenticate
     * must be wider than one client's, or it would only ever duplicate the per-client bucket.
     */
    boolean tryAcquire(String key, int allowance);
}

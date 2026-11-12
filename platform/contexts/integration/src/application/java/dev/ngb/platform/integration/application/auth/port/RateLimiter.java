package dev.ngb.platform.integration.application.auth.port;

/** Admits or rejects one token request for a key. Every instance shares the same store. */
public interface RateLimiter {

    boolean tryAcquire(String key);
}

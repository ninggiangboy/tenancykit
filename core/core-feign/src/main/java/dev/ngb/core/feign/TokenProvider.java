package dev.ngb.core.feign;

/**
 * The credential a client presents to another service. A suite token outlives one request, so it is cached until
 * shortly before it expires, and either party may rotate the secret without restarting the caller.
 */
public interface TokenProvider {

    /** @throws RuntimeException when the credential is not configured or cannot be obtained */
    String token();

    /** Whether this deployment holds enough configuration to obtain a token at all. */
    boolean configured();

    /**
     * Drops a cached token after the upstream refused it.
     *
     * @return false when the provider cannot produce a new token, so the caller must not retry
     */
    boolean invalidate();
}

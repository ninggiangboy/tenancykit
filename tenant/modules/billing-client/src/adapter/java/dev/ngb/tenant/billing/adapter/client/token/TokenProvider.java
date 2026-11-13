package dev.ngb.tenant.billing.adapter.client.token;

/**
 * The credential this Tenant System presents to Billing. It is a suite token issued by Platform, so a token
 * outlives one request and either party may rotate the secret without restarting this service.
 */
public interface TokenProvider {

    /** @throws IllegalStateException when the credential is not configured */
    String token();

    /** Whether this deployment holds enough configuration to obtain a token at all. */
    boolean configured();

    /**
     * Drops a cached token after the app refused it.
     *
     * @return false when the provider cannot produce a new token, so the caller must not retry
     */
    boolean invalidate();
}

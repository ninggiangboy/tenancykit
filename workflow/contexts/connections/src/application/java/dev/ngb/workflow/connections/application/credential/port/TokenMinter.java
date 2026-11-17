package dev.ngb.workflow.connections.application.credential.port;

import java.time.Instant;

/** Asks Platform for a token: client credentials, one audience, ten minutes. */
public interface TokenMinter {

    /**
     * @param clientId the Tenant's client id
     * @param secret the decrypted client secret
     * @param audience the app the token is for
     * @return the token and when it expires
     * @throws TokenRejectedException when Platform answers 401, which means the credential is wrong
     */
    Minted mint(String clientId, String secret, String audience);

    record Minted(String token, Instant expiresAt) {}
}

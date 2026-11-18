package dev.ngb.platform.integration.application.credential.query;

import dev.ngb.platform.integration.domain.credential.CredentialStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Credential metadata; it never carries the secret or its hash. */
public record CredentialView(
        UUID id,
        UUID tenantSystemId,
        String keyId,
        String label,
        Set<String> scopes,
        CredentialStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    public CredentialView {
        scopes = Set.copyOf(scopes);
    }
}

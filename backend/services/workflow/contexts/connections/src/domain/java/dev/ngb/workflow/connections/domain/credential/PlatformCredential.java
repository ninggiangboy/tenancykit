package dev.ngb.workflow.connections.domain.credential;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * The credential a Tenant registered so the engine can mint a token per audience. One per Tenant; the secret is an
 * envelope, and a token request that answers 401 marks it {@link CredentialStatus#INVALID} so steps fail fast instead of
 * hammering Platform.
 */
public record PlatformCredential(
        TenantId tenantId,
        String clientId,
        String secretCiphertext,
        String secretKeyId,
        CredentialStatus status,
        Instant updatedAt) {

    public PlatformCredential {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(secretCiphertext, "secretCiphertext");
        Objects.requireNonNull(secretKeyId, "secretKeyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean usable() {
        return status == CredentialStatus.ACTIVE;
    }

    public PlatformCredential invalidated(Instant now) {
        return new PlatformCredential(tenantId, clientId, secretCiphertext, secretKeyId, CredentialStatus.INVALID, now);
    }

    @Override
    public String toString() {
        return "PlatformCredential[tenantId=" + tenantId + ", clientId=" + clientId + ", status=" + status + "]";
    }
}

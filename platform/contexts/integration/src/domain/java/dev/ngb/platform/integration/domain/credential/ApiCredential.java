package dev.ngb.platform.integration.domain.credential;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** A Platform-issued secret that lets one Tenant System obtain suite tokens with a bounded set of scopes. */
public final class ApiCredential {

    private final ApiCredentialId id;
    private final TenantId tenantId;
    private final TenantSystemId tenantSystemId;
    private final String keyId;
    private final byte[] secretHash;
    private final Set<String> scopes;
    private final String label;
    private final Instant createdAt;
    private CredentialStatus status;
    private Instant expiresAt;
    private Instant revokedAt;
    private long version;

    private ApiCredential(
            ApiCredentialId id,
            TenantId tenantId,
            TenantSystemId tenantSystemId,
            String keyId,
            byte[] secretHash,
            Set<String> scopes,
            String label,
            CredentialStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.tenantSystemId = Objects.requireNonNull(tenantSystemId, "tenantSystemId");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.secretHash = Arrays.copyOf(Objects.requireNonNull(secretHash, "secretHash"), secretHash.length);
        this.scopes = Set.copyOf(scopes);
        Objects.requireNonNull(label, "label");
        if (label.length() > 100) {
            throw new IllegalArgumentException("label must be at most 100 characters");
        }
        this.label = label.strip();
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public static ApiCredential issue(
            ApiCredentialId id,
            TenantId tenantId,
            TenantSystemId tenantSystemId,
            ApiKey.Generated key,
            Set<String> scopes,
            String label,
            Instant now) {
        return new ApiCredential(
                id, tenantId, tenantSystemId, key.keyId(), ApiKey.hash(key.secret()), scopes, label,
                CredentialStatus.ACTIVE, now, null, null, 1);
    }

    public static ApiCredential restore(
            ApiCredentialId id,
            TenantId tenantId,
            TenantSystemId tenantSystemId,
            String keyId,
            byte[] secretHash,
            Set<String> scopes,
            String label,
            CredentialStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version) {
        return new ApiCredential(
                id, tenantId, tenantSystemId, keyId, secretHash, scopes, label, status, createdAt, expiresAt, revokedAt,
                version);
    }

    /** Constant-time comparison of the presented secret's digest with the stored digest. */
    public boolean matches(String presentedSecret) {
        return MessageDigest.isEqual(ApiKey.hash(presentedSecret), secretHash);
    }

    public boolean isUsable(Instant now) {
        return status == CredentialStatus.ACTIVE && (expiresAt == null || now.isBefore(expiresAt));
    }

    /**
     * Immediate and permanent.
     *
     * @return false when it was already revoked, so no write and no version bump happens
     */
    public boolean revoke(Instant now) {
        if (status != CredentialStatus.ACTIVE) {
            return false;
        }
        status = CredentialStatus.REVOKED;
        revokedAt = now;
        version++;
        return true;
    }

    /**
     * Ends the credential's life at {@code at} unless it already ends sooner. Used for rotation overlap.
     *
     * @return false when the end is already at or before {@code at}, so no write and no version bump happens
     */
    public boolean expireAt(Instant at) {
        if (expiresAt != null && !at.isBefore(expiresAt)) {
            return false;
        }
        expiresAt = at;
        version++;
        return true;
    }

    public ApiCredentialId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public TenantSystemId tenantSystemId() {
        return tenantSystemId;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] secretHash() {
        return Arrays.copyOf(secretHash, secretHash.length);
    }

    public Set<String> scopes() {
        return scopes;
    }

    public String label() {
        return label;
    }

    public CredentialStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "ApiCredential[id=" + id + ", keyId=" + keyId + ", status=" + status + "]";
    }
}

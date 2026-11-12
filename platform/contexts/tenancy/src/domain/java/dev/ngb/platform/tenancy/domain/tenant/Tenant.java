package dev.ngb.platform.tenancy.domain.tenant;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * An independent business space on the Platform. {@code version} is the aggregate's optimistic-lock column and is
 * copied into every {@code tenant.changed} event, so a replica can drop an event older than the row it holds.
 */
public final class Tenant {

    private final TenantId id;
    private final TenantCode code;
    private final Instant createdAt;
    private String name;
    private TenantStatus status;
    private long version;

    private Tenant(TenantId id, TenantCode code, String name, TenantStatus status, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    public static Tenant create(TenantId id, TenantCode code, String name, Instant now) {
        return new Tenant(id, code, name, TenantStatus.ACTIVE, now, 1);
    }

    /** Rebuilds a stored Tenant; persistence adapters use this, business code does not. */
    public static Tenant restore(TenantId id, TenantCode code, String name, TenantStatus status, Instant createdAt, long version) {
        return new Tenant(id, code, name, status, createdAt, version);
    }

    /** @return false when the name is unchanged, so no event is published for a no-op */
    public boolean rename(String newName) {
        String normalized = requireName(newName);
        if (normalized.equals(name)) {
            return false;
        }
        name = normalized;
        version++;
        return true;
    }

    /** Deactivating twice is allowed and changes nothing. */
    public boolean deactivate() {
        if (status == TenantStatus.DEACTIVATED) {
            return false;
        }
        status = TenantStatus.DEACTIVATED;
        version++;
        return true;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public TenantId id() {
        return id;
    }

    public TenantCode code() {
        return code;
    }

    public String name() {
        return name;
    }

    public TenantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("tenant name must be 1-200 characters");
        }
        return name.strip();
    }
}

package dev.ngb.platform.integration.domain.tenantsystem;

import dev.ngb.core.ddd.AggregateRoot;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.time.Instant;
import java.util.Objects;

/**
 * A third-party application integrated with one Tenant. It authenticates with API credentials exchanged for a
 * suite token. {@code version} is the optimistic-lock column copied into every {@code tenant-system.changed} event,
 * so a replica can drop an event older than the row it holds.
 */
public final class TenantSystem extends AggregateRoot<TenantSystemId> {

    private final TenantId tenantId;
    private final TenantSystemPurpose purpose;
    private final Instant createdAt;
    private String name;
    private TenantSystemStatus status;
    private long version;

    private TenantSystem(
            TenantSystemId id, TenantId tenantId, String name, TenantSystemPurpose purpose, TenantSystemStatus status,
            Instant createdAt, long version) {
        super(id);
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.name = requireName(name);
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    public static TenantSystem create(
            TenantSystemId id, TenantId tenantId, String name, TenantSystemPurpose purpose, Instant now) {
        return new TenantSystem(id, tenantId, name, purpose, TenantSystemStatus.ACTIVE, now, 1);
    }

    public static TenantSystem restore(
            TenantSystemId id, TenantId tenantId, String name, TenantSystemPurpose purpose, TenantSystemStatus status,
            Instant createdAt, long version) {
        return new TenantSystem(id, tenantId, name, purpose, status, createdAt, version);
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

    /** Disabling twice is allowed and changes nothing. It is the immediate kill switch for the system's tokens. */
    public boolean disable() {
        if (status == TenantSystemStatus.DISABLED) {
            return false;
        }
        status = TenantSystemStatus.DISABLED;
        version++;
        return true;
    }

    public boolean isActive() {
        return status == TenantSystemStatus.ACTIVE;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public TenantSystemPurpose purpose() {
        return purpose;
    }

    public TenantSystemStatus status() {
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
        if (name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("tenant system name must be 1-100 characters");
        }
        return name.strip();
    }
}

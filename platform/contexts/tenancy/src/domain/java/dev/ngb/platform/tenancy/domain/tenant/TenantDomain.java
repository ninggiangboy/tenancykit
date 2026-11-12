package dev.ngb.platform.tenancy.domain.tenant;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maps one hostname to the Platform or to one Tenant. */
public final class TenantDomain {

    private final UUID id;
    private final TenantId tenantId;
    private final Hostname hostname;
    private final DomainKind kind;
    private final Instant createdAt;
    private final long version;

    private TenantDomain(UUID id, TenantId tenantId, Hostname hostname, DomainKind kind, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.hostname = Objects.requireNonNull(hostname, "hostname");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if ((kind == DomainKind.PLATFORM) != (tenantId == null)) {
            throw new IllegalArgumentException("a PLATFORM domain has no tenant; every other kind has one");
        }
        this.tenantId = tenantId;
        this.version = version;
    }

    public static TenantDomain platform(UUID id, Hostname hostname, Instant now) {
        return new TenantDomain(id, null, hostname, DomainKind.PLATFORM, now, 1);
    }

    public static TenantDomain forTenant(UUID id, TenantId tenantId, Hostname hostname, DomainKind kind, Instant now) {
        return new TenantDomain(id, Objects.requireNonNull(tenantId, "tenantId"), hostname, kind, now, 1);
    }

    public static TenantDomain restore(
            UUID id, TenantId tenantId, Hostname hostname, DomainKind kind, Instant createdAt, long version) {
        return new TenantDomain(id, tenantId, hostname, kind, createdAt, version);
    }

    public UUID id() {
        return id;
    }

    public Optional<TenantId> tenantId() {
        return Optional.ofNullable(tenantId);
    }

    public Hostname hostname() {
        return hostname;
    }

    public DomainKind kind() {
        return kind;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}

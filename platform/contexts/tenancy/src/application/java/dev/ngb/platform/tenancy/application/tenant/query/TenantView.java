package dev.ngb.platform.tenancy.application.tenant.query;

import java.time.Instant;
import java.util.UUID;

/** One Tenant as a reader sees it. */
public record TenantView(UUID id, String code, String name, String status, Instant createdAt, long version) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

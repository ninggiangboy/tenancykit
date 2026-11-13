package dev.ngb.platform.integration.application.tenantsystem.query;

import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemStatus;
import java.time.Instant;
import java.util.UUID;

/** A tenant system as the admin GET handlers show it. */
public record TenantSystemView(UUID id, UUID tenantId, String name, TenantSystemStatus status, Instant createdAt, long version) {}

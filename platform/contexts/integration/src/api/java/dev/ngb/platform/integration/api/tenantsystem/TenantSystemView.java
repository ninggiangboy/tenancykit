package dev.ngb.platform.integration.api.tenantsystem;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant system as the admin GET handlers show it. The status is the code the database holds, so the contract names
 * no domain type and the snapshot stream and the admin API serialise the same shape.
 */
public record TenantSystemView(UUID id, UUID tenantId, String name, String status, Instant createdAt, long version) {}

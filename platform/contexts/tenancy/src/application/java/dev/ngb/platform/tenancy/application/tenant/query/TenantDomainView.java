package dev.ngb.platform.tenancy.application.tenant.query;

import java.time.Instant;
import java.util.UUID;

/** One hostname mapping as a reader sees it. */
public record TenantDomainView(UUID id, UUID tenantId, String hostname, String kind, Instant createdAt, long version) {}

package dev.ngb.platform.tenancy.application.entitlement.query;

import java.util.UUID;

/** One entitlement as a reader and a replica see it. */
public record EntitlementView(UUID tenantId, String appCode, boolean enabled, long version) {}

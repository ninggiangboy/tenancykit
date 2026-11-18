package dev.ngb.platform.tenancy.application.entitlement.query;

/** One app of the suite and whether a Tenant may use it; {@code version} is 0 while the tenant has never touched it. */
public record AppEntitlementView(String appCode, boolean enabled, long version) {}

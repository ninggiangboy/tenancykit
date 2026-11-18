package dev.ngb.platform.integration.domain.tenantsystem;

/**
 * What a Tenant System is for. {@code GENERIC} is a Tenant's own business system; {@code WORKFLOW} is the dedicated
 * system that drives the workflow app. The purpose is fixed when the system is created and cannot be changed, and it
 * narrows which codes a credential of that system may carry.
 */
public enum TenantSystemPurpose {
    GENERIC,
    WORKFLOW
}

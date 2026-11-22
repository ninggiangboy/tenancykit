package dev.ngb.platform.tenancy.api.provisioning;

import java.util.UUID;

/** The tenancy contract other contexts use: create a Tenant. */
public interface TenantProvisioning {

    /** @param id the id the Tenant is created with, chosen by the caller because something else must record it first */
    void createTenant(UUID id, String code, String name);
}

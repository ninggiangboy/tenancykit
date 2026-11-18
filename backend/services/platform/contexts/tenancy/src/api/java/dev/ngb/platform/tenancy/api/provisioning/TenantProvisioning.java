package dev.ngb.platform.tenancy.api.provisioning;

import java.util.UUID;

/** The tenancy contract other contexts use: create a Tenant and answer its id. */
public interface TenantProvisioning {

    UUID createTenant(String code, String name);
}

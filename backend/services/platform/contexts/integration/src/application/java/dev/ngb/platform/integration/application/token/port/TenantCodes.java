package dev.ngb.platform.integration.application.token.port;

import dev.ngb.core.id.TenantId;
import java.util.Optional;

/** Reads the code of a Tenant, which a Tenant System token carries as the {@code tenant_code} claim. */
public interface TenantCodes {

    Optional<String> codeOf(TenantId tenantId);
}

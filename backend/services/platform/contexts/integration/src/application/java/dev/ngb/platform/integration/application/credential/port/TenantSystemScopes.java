package dev.ngb.platform.integration.application.credential.port;

import dev.ngb.core.id.TenantSystemId;
import java.util.Optional;

/**
 * Answers whether a permission code may be granted to a Tenant System credential. Backed by the suite's permission
 * catalogs, so only codes with {@code grantable_to_tenant_system: true} can ever reach a credential.
 */
public interface TenantSystemScopes {

    boolean grantable(String code);
}

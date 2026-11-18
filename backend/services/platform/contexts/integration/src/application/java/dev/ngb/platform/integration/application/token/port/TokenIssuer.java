package dev.ngb.platform.integration.application.token.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.util.Set;

/** Signs the suite tokens. The signing key lives only in the application that hosts Platform. */
public interface TokenIssuer {

    record Issued(String token, long expiresIn) {}

    /** A token for a service client of the suite; its subject is {@code service/<id>}. */
    Issued issueService(String clientId, Set<String> scopes, String audience);

    /**
     * A token for one Tenant System; its subject is {@code tenant-system/<id>} and it carries the tenant's id and
     * code, so a consumer can bind the token to the host it arrives on.
     */
    Issued issueTenantSystem(
            TenantSystemId systemId, TenantId tenantId, String tenantCode, Set<String> scopes, String audience);
}

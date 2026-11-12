package dev.ngb.platform.integration.application.credential.query;

import java.util.List;
import java.util.UUID;

/** Read side of credentials: metadata only, never the secret or its digest. */
public interface CredentialReadRepository {

    List<CredentialView> findByTenantSystem(UUID tenantSystemId);
}

package dev.ngb.platform.integration.application.credential.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.UUID;

/** Read side of credentials: metadata only, never the secret or its digest. */
public interface CredentialReadRepository {

    /**
     * One page of the credentials of one Tenant System, filtered, sorted and paged by the list-query contract.
     *
     * @param tenantSystemId the system whose credentials are listed, fixed by the request path
     */
    Page<CredentialView> list(ListQuery query, UUID tenantSystemId);
}

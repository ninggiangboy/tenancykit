package dev.ngb.platform.integration.application.credential.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.integration.api.tenantsystem.TenantSystemReadRepository;

/** Reads a tenant system's credentials for the admin GET handler. */
public class CredentialQueries {

    private final UnitOfWork unitOfWork;
    private final CredentialReadRepository credentials;
    private final TenantSystemReadRepository systems;

    public CredentialQueries(UnitOfWork unitOfWork, CredentialReadRepository credentials, TenantSystemReadRepository systems) {
        this.unitOfWork = unitOfWork;
        this.credentials = credentials;
        this.systems = systems;
    }

    /** One page of the system's credentials; a system the caller cannot see is a 404, never an empty page. */
    public Page<CredentialView> list(ListQuery query, TenantSystemId tenantSystemId) {
        return unitOfWork.transactional(() -> {
            systems.findById(tenantSystemId.value()).orElseThrow(() -> new NotFoundException("tenant system not found"));
            return credentials.list(query, tenantSystemId.value());
        });
    }
}

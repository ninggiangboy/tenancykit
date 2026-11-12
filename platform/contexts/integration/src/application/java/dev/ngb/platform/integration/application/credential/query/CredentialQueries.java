package dev.ngb.platform.integration.application.credential.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.integration.application.tenantsystem.query.TenantSystemReadRepository;
import java.util.List;

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

    public List<CredentialView> list(TenantSystemId tenantSystemId) {
        return unitOfWork.transactional(() -> {
            systems.findById(tenantSystemId.value()).orElseThrow(() -> new NotFoundException("tenant system not found"));
            return credentials.findByTenantSystem(tenantSystemId.value());
        });
    }
}

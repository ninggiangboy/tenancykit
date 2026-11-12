package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.platform.tenancy.application.tenant.query.TenantDomainView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import java.util.Optional;

/**
 * Resolves a hostname to the scope it serves. It reads before any tenant is bound, because the hostname is what
 * decides the tenant; a hostname that is not registered resolves to nothing.
 */
public class ResolveHostUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantReadRepository tenants;

    public ResolveHostUseCase(UnitOfWork unitOfWork, TenantReadRepository tenants) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
    }

    public Optional<RequestScope> execute(String hostname) {
        return unitOfWork.transactional(() -> tenants.findDomainByHostname(hostname))
                .flatMap(ResolveHostUseCase::scopeOf);
    }

    private static Optional<RequestScope> scopeOf(TenantDomainView domain) {
        if ("PLATFORM".equals(domain.kind())) {
            return Optional.of(RequestScope.platform());
        }
        return domain.tenantId() == null
                ? Optional.empty()
                : Optional.of(RequestScope.tenant(new TenantId(domain.tenantId())));
    }
}

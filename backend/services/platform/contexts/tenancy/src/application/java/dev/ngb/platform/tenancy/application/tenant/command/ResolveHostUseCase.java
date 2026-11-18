package dev.ngb.platform.tenancy.application.tenant.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.platform.tenancy.application.tenant.query.TenantDomainView;
import dev.ngb.platform.tenancy.application.tenant.query.TenantReadRepository;
import dev.ngb.platform.tenancy.application.tenant.query.TenantView;
import java.util.Optional;

/**
 * Resolves a hostname to the scope it serves. It reads before any tenant is bound, because the hostname is what
 * decides the tenant; a hostname that is not registered, or that belongs to a deactivated tenant, resolves to nothing.
 */
public class ResolveHostUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantReadRepository tenants;

    public ResolveHostUseCase(UnitOfWork unitOfWork, TenantReadRepository tenants) {
        this.unitOfWork = unitOfWork;
        this.tenants = tenants;
    }

    public Optional<RequestScope> execute(String hostname) {
        return unitOfWork.transactional(() -> tenants.findDomainByHostname(hostname).flatMap(this::scopeOf));
    }

    /** A host of a deactivated tenant resolves to nothing, so callers answer with the same generic error. */
    private Optional<RequestScope> scopeOf(TenantDomainView domain) {
        if ("PLATFORM".equals(domain.kind())) {
            return Optional.of(RequestScope.platform());
        }
        if (domain.tenantId() == null) {
            return Optional.empty();
        }
        return tenants.findById(domain.tenantId())
                .filter(TenantView::isActive)
                .map(tenant -> RequestScope.tenant(new TenantId(domain.tenantId())));
    }
}

package dev.ngb.core.replica.web;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.replica.ReplicaTables;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Hostname to {@link RequestScope}, from the replicated {@code tenant_domains}. A {@code PLATFORM} domain answers the
 * platform scope; an unknown host answers nothing, which the filter turns into one generic 404.
 */
public class ReplicaHostResolver {

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;

    public ReplicaHostResolver(UnitOfWork unitOfWork, DSLContext dsl) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
    }

    public Optional<RequestScope> resolve(String hostname) {
        return unitOfWork.transactional(() -> dsl
                .select(ReplicaTables.DOMAIN_KIND, ReplicaTables.DOMAIN_TENANT_ID)
                .from(ReplicaTables.TENANT_DOMAINS)
                .where(ReplicaTables.DOMAIN_HOSTNAME.eq(hostname.toLowerCase(java.util.Locale.ROOT)))
                .limit(1)
                .fetchOptional())
                .flatMap(row -> "PLATFORM".equals(row.value1())
                        ? Optional.of(RequestScope.platform())
                        : Optional.ofNullable(row.value2()).map(id -> RequestScope.tenant(new TenantId(id))));
    }
}

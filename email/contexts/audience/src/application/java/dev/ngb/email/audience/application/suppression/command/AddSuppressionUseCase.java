package dev.ngb.email.audience.application.suppression.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.email.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.email.audience.domain.receiver.NormalizedAddress;
import dev.ngb.email.audience.domain.suppression.Suppression;
import dev.ngb.email.audience.domain.suppression.SuppressionReason;
import dev.ngb.email.audience.domain.suppression.SuppressionScope;

/** Blocks an address on the Tenant's instruction; an ALL block stops transactional mail too. */
public class AddSuppressionUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SuppressionRepository suppressions;
    private final Clock clock;

    public AddSuppressionUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SuppressionRepository suppressions,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.suppressions = suppressions;
        this.clock = clock;
    }

    public void execute(String email, SuppressionScope scope) {
        TenantId tenantId = tenantContext.requireTenant();
        String normalized = NormalizedAddress.normalize(email);
        Suppression suppression =
                new Suppression(tenantId, normalized, scope, SuppressionReason.MANUAL, clock.now());
        unitOfWork.transactional(() -> suppressions.save(suppression));
    }
}

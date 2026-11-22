package dev.ngb.loyalty.program.application.tier.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.program.api.tier.TierListRevisions;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.domain.program.Program;

/**
 * The tier list's revision mark, as the run that decides every member's tier reads and clears it.
 *
 * <p>A tenant without a program owes no pass: there is no list to apply. The clear keeps the program's version moving with
 * it, so the list's ETag never stands still while its mark does.
 */
public class TierListRevisionsService implements TierListRevisions {

    private final UnitOfWork unitOfWork;
    private final ProgramRepository programs;
    private final TenantContext tenantContext;
    private final Clock clock;

    public TierListRevisionsService(
            UnitOfWork unitOfWork, ProgramRepository programs, TenantContext tenantContext, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public boolean reviewPending() {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(
                () -> programs.lock(tenantId).map(Program::tiersDirty).orElse(false));
    }

    @Override
    public void reviewed() {
        TenantId tenantId = tenantContext.requireTenant();
        unitOfWork.transactional(() -> programs.lock(tenantId).ifPresent(program -> {
            if (program.tiersDirty()) {
                program.markTiersClean(clock.now());
                programs.update(program);
            }
        }));
    }
}

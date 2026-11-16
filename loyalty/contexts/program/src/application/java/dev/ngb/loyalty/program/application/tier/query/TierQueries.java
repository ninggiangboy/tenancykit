package dev.ngb.loyalty.program.application.tier.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.query.ProgramReadRepository;

/** Reads the current tenant's tier list for the API's GET handler. */
public class TierQueries {

    private final UnitOfWork unitOfWork;
    private final ProgramReadRepository programs;
    private final TierReadRepository tiers;

    public TierQueries(UnitOfWork unitOfWork, ProgramReadRepository programs, TierReadRepository tiers) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.tiers = tiers;
    }

    /** @throws ProgramRefusal {@code PROGRAM_NOT_CONFIGURED} when a tier list cannot exist yet */
    public TierListView list() {
        return unitOfWork.transactional(() -> {
            ProgramView program = programs.find()
                    .orElseThrow(() -> ProgramRefusal.notFound(
                            "PROGRAM_NOT_CONFIGURED", "No points program is configured for this tenant yet."));
            return new TierListView(tiers.findAll(), program.version());
        });
    }
}

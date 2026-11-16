package dev.ngb.loyalty.program.application.program.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;

/** Reads the current tenant's program for the API's GET handler. */
public class ProgramQueries {

    private final UnitOfWork unitOfWork;
    private final ProgramReadRepository programs;

    public ProgramQueries(UnitOfWork unitOfWork, ProgramReadRepository programs) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
    }

    /** @throws ProgramRefusal {@code PROGRAM_NOT_CONFIGURED} when the tenant has not configured a program yet */
    public ProgramView get() {
        return unitOfWork.transactional(() -> programs.find()
                .orElseThrow(() -> ProgramRefusal.notFound(
                        "PROGRAM_NOT_CONFIGURED", "No points program is configured for this tenant yet.")));
    }
}

package dev.ngb.attribution.spend.application.entry.command;

import dev.ngb.attribution.spend.application.entry.port.SpendRepository;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.LocalDate;
import java.util.UUID;

/** Deletes the manual cost of one campaign and day, leaving any imported row in place. */
public class DeleteManualSpendUseCase {

    private final UnitOfWork unitOfWork;
    private final SpendRepository costs;

    public DeleteManualSpendUseCase(UnitOfWork unitOfWork, SpendRepository costs) {
        this.unitOfWork = unitOfWork;
        this.costs = costs;
    }

    /** @return how many rows were deleted, 0 or 1 */
    public int execute(UUID campaignId, LocalDate day) {
        return unitOfWork.transactional(() -> costs.deleteManual(campaignId, day));
    }
}

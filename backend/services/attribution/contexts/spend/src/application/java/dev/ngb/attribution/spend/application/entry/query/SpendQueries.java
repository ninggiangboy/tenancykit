package dev.ngb.attribution.spend.application.entry.query;

import dev.ngb.core.runtime.UnitOfWork;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The cost read endpoints, read inside a tenant-scoped transaction. */
public class SpendQueries {

    private final UnitOfWork unitOfWork;
    private final SpendReadRepository costs;

    public SpendQueries(UnitOfWork unitOfWork, SpendReadRepository costs) {
        this.unitOfWork = unitOfWork;
        this.costs = costs;
    }

    public List<SpendView> list(UUID campaignId, LocalDate fromDay, LocalDate toDayInclusive) {
        return unitOfWork.transactional(() -> costs.list(campaignId, fromDay, toDayInclusive));
    }
}

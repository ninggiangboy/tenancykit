package dev.ngb.attribution.links.application.link.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads a link's statistics in one snapshot, so the totals and the daily series agree with each other. */
public class LinkStatsQueries {

    private final UnitOfWork unitOfWork;
    private final LinkStatsReadRepository stats;

    public LinkStatsQueries(UnitOfWork unitOfWork, LinkStatsReadRepository stats) {
        this.unitOfWork = unitOfWork;
        this.stats = stats;
    }

    public LinkStatsView stats(UUID linkId) {
        return unitOfWork.transactional(() -> stats.stats(linkId)
                .orElseThrow(() -> new NotFoundException("No link exists here.")));
    }
}

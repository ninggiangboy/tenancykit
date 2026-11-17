package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads a link's statistics in one snapshot, so its total and its daily series agree. */
public class LinkStatsQueries {

    private final UnitOfWork unitOfWork;
    private final ReportReadRepository reports;
    private final dev.ngb.attribution.links.api.link.LinkMetadataReader links;
    private final dev.ngb.attribution.insight.api.rollup.RollupStateReader state;

    public LinkStatsQueries(
            UnitOfWork unitOfWork,
            ReportReadRepository reports,
            dev.ngb.attribution.links.api.link.LinkMetadataReader links,
            dev.ngb.attribution.insight.api.rollup.RollupStateReader state) {
        this.unitOfWork = unitOfWork;
        this.reports = reports;
        this.links = links;
        this.state = state;
    }

    public LinkStatsView stats(UUID linkId, java.time.Instant now) {
        return unitOfWork.transactional(() -> {
            var link = links.link(linkId).orElseThrow(() -> new NotFoundException("No link exists here."));
            return reports.linkStats(linkId, link.status(), link.maxClicks(), now, state.dataThrough());
        });
    }
}

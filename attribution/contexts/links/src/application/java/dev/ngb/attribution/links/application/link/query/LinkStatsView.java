package dev.ngb.attribution.links.application.link.query;

import java.time.LocalDate;
import java.util.List;

/** A link's click totals and its last thirty days, read in one snapshot so the counts cannot disagree. */
public record LinkStatsView(
        long clicksCounted,
        Long maxClicks,
        String status,
        List<DailyCount> last30Days) {

    /** Clicks recorded on one day, oldest first. */
    public record DailyCount(LocalDate day, long clicks) {}
}

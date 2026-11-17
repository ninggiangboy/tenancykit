package dev.ngb.attribution.insight.application.report.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A link's statistics: its human click total, the last thirty days and the bot clicks the total left out.
 *
 * @param maxClicks the cap, or null when the link is uncapped
 */
public record LinkStatsView(
        UUID linkId,
        String status,
        Long maxClicks,
        long clicks,
        List<DailyCount> last30Days,
        long botClicksExcluded,
        Instant dataThrough) {

    public LinkStatsView {
        last30Days = List.copyOf(last30Days);
    }

    /** Clicks recorded on one UTC day, oldest first. */
    public record DailyCount(LocalDate day, long clicks) {}
}

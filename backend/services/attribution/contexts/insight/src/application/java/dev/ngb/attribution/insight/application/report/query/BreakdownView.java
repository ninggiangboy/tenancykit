package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A breakdown by one dimension.
 *
 * @param botClicksExcluded how many BOT and SUSPECT clicks the human-only default left out of these rows
 */
public record BreakdownView(
        String dimension,
        Instant from,
        Instant to,
        String trafficClass,
        List<Row> rows,
        long botClicksExcluded,
        Instant dataThrough) {

    public BreakdownView {
        rows = List.copyOf(rows);
    }

    /** One dimension value's metrics. A metric not requested is null. */
    public record Row(
            String key,
            Long clicks,
            Long pageViews,
            Long sessions,
            Long uniqueVisitorDays,
            BigDecimal conversions,
            BigDecimal creditedValue,
            BigDecimal multiTouchShare) {}
}

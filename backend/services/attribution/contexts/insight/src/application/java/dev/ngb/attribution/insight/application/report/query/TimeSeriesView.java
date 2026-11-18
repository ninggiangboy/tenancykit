package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A metric series. A metric the caller did not ask for is null rather than zero, so a client can tell "not requested"
 * from "measured zero".
 *
 * @param dataThrough when the rollups last finished; a report is one to two minutes behind by design
 * @param botClicksExcluded how many BOT and SUSPECT clicks the human-only default left out of the series
 */
public record TimeSeriesView(
        String granularity,
        Instant from,
        Instant to,
        String trafficClass,
        List<Point> points,
        Instant dataThrough,
        long botClicksExcluded) {

    public TimeSeriesView {
        points = List.copyOf(points);
    }

    /**
     * One bucket.
     *
     * @param uniqueVisitorDays distinct visitor days in the bucket; over several days it is the sum of daily values and
     *     the API names it {@code unique_visitor_days}
     * @param multiTouchShare the share of conversions whose chain has more than one click, or null when none converted
     */
    public record Point(
            Instant bucket,
            Long clicks,
            Long pageViews,
            Long sessions,
            Long uniqueVisitorDays,
            BigDecimal conversions,
            BigDecimal creditedValue,
            BigDecimal multiTouchShare) {}
}

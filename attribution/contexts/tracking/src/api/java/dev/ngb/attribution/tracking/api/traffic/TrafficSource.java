package dev.ngb.attribution.tracking.api.traffic;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The traffic facts the insight context rolls up: clicks, page views, sessions and uniques per hour, the referrers of a
 * day, and the first two steps of the funnel.
 *
 * <p>This is the only shape insight sees of tracking's raw tables. It never reads a click row itself; it asks here for
 * aggregates over a bounded window, which is what keeps a report from scanning the event tables.
 */
public interface TrafficSource {

    /**
     * The traffic per hour in {@code [from, to)}, split by the click's dimensions and traffic class.
     *
     * <p>A page view or a session with no click is still counted, under a null link, campaign and destination: it is
     * real traffic even when it cannot be tied to a link.
     *
     * @param from the first instant, usually the start of an hour
     * @param to the exclusive end, usually the start of the current hour
     */
    List<TrafficBucket> buckets(Instant from, Instant to);

    /**
     * The human clicks per referrer domain and link for each UTC day in {@code [fromDay, toDayExclusive)}.
     *
     * <p>The adapter keeps the top fifty domains per link and day and folds the rest into {@code other}, because a
     * referrer list is unbounded while a rollup must stay small.
     */
    List<ReferrerCount> referrers(LocalDate fromDay, LocalDate toDayExclusive);

    /**
     * The first two funnel steps for one campaign or link: non-bot clicks, and the clicks that have a page view after
     * them.
     *
     * @param campaignId the campaign to narrow to, or null
     * @param linkId the link to narrow to, or null
     * @param from the first click instant
     * @param to the exclusive end of the click window
     */
    FunnelCounts funnel(UUID campaignId, UUID linkId, Instant from, Instant to);

    /**
     * One hourly bucket.
     *
     * @param visitorDays distinct daily visitor hashes seen in this bucket; at day granularity the report sums them and
     *     names the metric {@code unique_visitor_days}
     */
    record TrafficBucket(
            Instant bucket,
            UUID linkId,
            UUID campaignId,
            UUID destinationId,
            String deviceClass,
            String country,
            String entry,
            String trafficClass,
            long clicks,
            long pageViews,
            long sessions,
            long visitorDays) {}

    /**
     * One referrer's human clicks on one day.
     *
     * @param referrerDomain the folded domain, {@code other} for the long tail, or {@code (direct)} when none was sent
     */
    record ReferrerCount(LocalDate day, UUID linkId, String referrerDomain, long clicks) {}

    /**
     * The funnel's first two steps, over human clicks only.
     *
     * @param botClicks the bot and suspect clicks in the same window, which the steps left out
     */
    record FunnelCounts(long clicks, long landed, long botClicks) {}
}

package dev.ngb.attribution.attribution.api.conversion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The conversion and credit facts the insight context rolls up and reports.
 *
 * <p>Credits are read here, never by joining the conversion tables from another context. A credit's campaign and link
 * are the ones that received it, which may differ from the campaign that owns the conversion; the report is about where
 * the value was credited, so those are the columns a rollup groups by.
 */
public interface ConversionRollupSource {

    /**
     * The credits per UTC day, credited campaign, link, goal, model and currency.
     *
     * @param fromDay the first day, inclusive
     * @param toDayExclusive the first day after the window
     */
    List<ConversionBucket> buckets(LocalDate fromDay, LocalDate toDayExclusive);

    /**
     * The cohort cells for one owning campaign: the UTC week of the first click in a conversion's chain, and how many
     * weeks after it the conversion happened.
     *
     * @param periods the largest week index to return, at most 12
     */
    List<CohortCell> cohorts(UUID campaignId, LocalDate fromDay, LocalDate toDayExclusive, int periods);

    /**
     * How many distinct clicks of one campaign or link received a credit for a conversion in the window. This is the
     * funnel's third step; a conversion whose credits are still pending contributes nothing.
     *
     * @param goalId one goal to narrow to, or null for every goal
     */
    long convertedClicks(UUID campaignId, UUID linkId, UUID goalId, Instant from, Instant to);

    /**
     * One credited group of a day.
     *
     * @param conversions distinct conversions that credited this campaign and link
     * @param multiTouchConversions the subset whose chain has more than one click; the report divides the two for
     *     {@code multi_touch_share}
     */
    record ConversionBucket(
            LocalDate day,
            UUID campaignId,
            UUID linkId,
            UUID goalId,
            String model,
            String currency,
            BigDecimal conversions,
            BigDecimal creditedValue,
            BigDecimal multiTouchConversions) {}

    /** One cohort cell. */
    record CohortCell(LocalDate cohortWeek, int week, long conversions, BigDecimal value) {}
}

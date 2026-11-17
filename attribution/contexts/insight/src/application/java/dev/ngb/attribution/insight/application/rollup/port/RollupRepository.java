package dev.ngb.attribution.insight.application.rollup.port;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The rollup store: replace one window's traffic, referrers and conversions, advance the cursor and drop expired rollups.
 *
 * <p>A replacement is a delete of the whole window followed by its insert, so a late event inside the window changes the
 * numbers on the next run and an interrupted run never leaves half a bucket beside its replacement.
 */
public interface RollupRepository {

    /** Replaces every hourly bucket in {@code [from, to)} with the given rows. */
    void replaceTraffic(Instant from, Instant to, List<TrafficSource.TrafficBucket> buckets);

    /** Replaces every referrer row whose day falls in {@code [fromDay, toDayExclusive)}. */
    void replaceReferrers(LocalDate fromDay, LocalDate toDayExclusive, List<TrafficSource.ReferrerCount> rows);

    /** Replaces every conversion row whose day falls in {@code [fromDay, toDayExclusive)}. */
    void replaceConversions(
            LocalDate fromDay, LocalDate toDayExclusive, List<ConversionRollupSource.ConversionBucket> rows);

    /** Moves the deployment-wide cursor forward and records when the run finished. */
    void advanceCursor(LocalDate rolledUpThrough, Instant dataThrough);

    /** Deletes rollup rows older than the cutoff, which is how the twenty-five-month retention is enforced. */
    void deleteOlderThan(LocalDate cutoff);
}

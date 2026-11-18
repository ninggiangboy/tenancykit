package dev.ngb.attribution.insight.application.rollup.command;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import dev.ngb.attribution.insight.api.rollup.RollupStateReader;
import dev.ngb.attribution.insight.application.rollup.port.RollupRepository;
import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Recomputes one tenant's trailing rollups, with a bounded catch-up so the cursor never passes a day no run absorbed.
 *
 * <p>Every run rebuilds at least the trailing three hours of traffic and the last three days of conversions, so a late
 * click and a recomputed credit are absorbed. When the cursor is behind — a fresh deployment or a worker that was down —
 * the run starts at the first day after the cursor (or, with no cursor, at the raw-retention horizon) and rebuilds up to
 * now. Only then does it move the cursor to the last day whose three-hour grace has passed, which is the day retention
 * may drop.
 *
 * <p>All buckets are UTC. A tenant that needs its own day reads hourly data; that is the recorded non-goal.
 */
public class RunRollupUseCase {

    private static final int TRAILING_HOURS = 3;
    private static final int CONVERSION_DAYS = 3;
    private static final int RAW_RETENTION_DAYS = 90;
    private static final int RETENTION_MONTHS = 25;

    public record Result(
            int trafficBuckets, int referrers, int conversions, LocalDate rolledUpThrough, Instant dataThrough) {}

    private final UnitOfWork unitOfWork;
    private final TrafficSource traffic;
    private final ConversionRollupSource conversions;
    private final RollupRepository rollups;
    private final RollupStateReader state;

    public RunRollupUseCase(
            UnitOfWork unitOfWork,
            TrafficSource traffic,
            ConversionRollupSource conversions,
            RollupRepository rollups,
            RollupStateReader state) {
        this.unitOfWork = unitOfWork;
        this.traffic = traffic;
        this.conversions = conversions;
        this.rollups = rollups;
        this.state = state;
    }

    public Result execute(Instant now) {
        return unitOfWork.transactional(() -> {
            // Whole hours only: a partial current hour would be replaced by the next run and would make a report depend
            // on the second it was read.
            Instant toBucket = now.truncatedTo(ChronoUnit.HOURS);
            Instant trailingFrom = toBucket.minus(TRAILING_HOURS, ChronoUnit.HOURS);
            LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate cursor = state.rolledUpThrough();

            // The first day this run must cover: the day after the cursor, or the raw-retention horizon on a first run.
            LocalDate catchUpFrom = cursor == null ? today.minusDays(RAW_RETENTION_DAYS) : cursor.plusDays(1);
            Instant fromBucket = catchUpFrom.isBefore(today)
                    ? catchUpFrom.atStartOfDay().toInstant(ZoneOffset.UTC)
                    : trailingFrom;
            if (fromBucket.isAfter(trailingFrom)) {
                fromBucket = trailingFrom;
            }

            List<TrafficSource.TrafficBucket> buckets = traffic.buckets(fromBucket, toBucket);
            rollups.replaceTraffic(fromBucket, toBucket, buckets);

            LocalDate referrerFrom = fromBucket.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate referrerToExclusive = today.plusDays(1);
            List<TrafficSource.ReferrerCount> referrers = traffic.referrers(referrerFrom, referrerToExclusive);
            rollups.replaceReferrers(referrerFrom, referrerToExclusive, referrers);

            LocalDate conversionFrom = catchUpFrom.isBefore(today.minusDays(CONVERSION_DAYS))
                    ? catchUpFrom
                    : today.minusDays(CONVERSION_DAYS);
            LocalDate conversionToExclusive = today.plusDays(1);
            List<ConversionRollupSource.ConversionBucket> conversionBuckets =
                    conversions.buckets(conversionFrom, conversionToExclusive);
            rollups.replaceConversions(conversionFrom, conversionToExclusive, conversionBuckets);

            LocalDate through = graceDay(now);
            if (cursor == null || through.isAfter(cursor)) {
                rollups.advanceCursor(through, now);
            }
            rollups.deleteOlderThan(today.minusMonths(RETENTION_MONTHS));

            return new Result(buckets.size(), referrers.size(), conversionBuckets.size(), through, now);
        });
    }

    /**
     * The last day whose three-hour grace has passed: a late event is absorbed until three hours after midnight, so the
     * previous day is safe. This is the day retention may drop, and the run has covered every day up to it.
     */
    static LocalDate graceDay(Instant now) {
        return now.minus(TRAILING_HOURS, ChronoUnit.HOURS)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .minusDays(1);
    }
}

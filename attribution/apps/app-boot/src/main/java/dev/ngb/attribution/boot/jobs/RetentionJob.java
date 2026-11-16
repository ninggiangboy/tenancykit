package dev.ngb.attribution.boot.jobs;

import dev.ngb.attribution.boot.config.AttributionSettings;
import dev.ngb.attribution.boot.rollup.RollupCursor;
import dev.ngb.attribution.tracking.application.ingest.port.PartitionStore;
import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops the click partitions that have passed retention.
 *
 * <p>Raw retention is a deployment setting, not a Tenant setting, because partitions are shared across Tenants; a
 * Tenant's earlier deletion is the erasure endpoint. The job drops a partition only when the rollup cursor has passed
 * its whole day, so a report can still read a day that has not been rolled up. Until the rollup exists (a later phase)
 * the cursor check keeps the job from dropping anything and it says so once per run.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private final PartitionStore partitions;
    private final RollupCursor cursor;
    private final int retentionDays;
    private final Clock clock;

    RetentionJob(
            PartitionStore partitions,
            RollupCursor cursor,
            AttributionSettings settings,
            Clock clock,
            @Value("${attribution.retention.days:0}") int retentionOverride) {
        this.partitions = partitions;
        this.cursor = cursor;
        this.retentionDays = retentionOverride > 0 ? retentionOverride : settings.defaults().rawRetentionDays();
        this.clock = clock;
    }

    /** Once a day is enough: a partition becomes eligible at midnight and nothing depends on the exact minute. */
    @Scheduled(fixedDelayString = "${attribution.retention.interval:PT24H}", initialDelayString = "PT5M")
    public void run() {
        runAt(clock.now());
    }

    /** The sweep for one instant, exposed so a test drives it without waiting for a day. */
    public int runAt(Instant now) {
        var cutoff = now.minus(retentionDays, ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate rolledUpThrough = cursor.rolledUpThrough();
        if (rolledUpThrough == null) {
            // No rollup has run, so dropping a partition could remove a day a report still needs. This is the state a
            // deployment is in until the rollup phase lands, so it is said once per run rather than as an error.
            LOG.info("retention: no rollup cursor yet, so no partition is dropped (cutoff {})", cutoff);
            return 0;
        }
        var safeCutoff = cutoff.isBefore(rolledUpThrough) ? cutoff : rolledUpThrough;
        int dropped = partitions.drop(PartitionStore.CLICK_EVENTS, safeCutoff);
        if (dropped > 0) {
            LOG.info("retention: dropped {} click partition(s) older than {}", dropped, safeCutoff);
        }
        return dropped;
    }
}

package dev.ngb.attribution.boot.jobs;

import dev.ngb.attribution.insight.application.rollup.command.RunRollupUseCase;
import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recomputes every tenant's trailing rollups once a minute.
 *
 * <p>Each tenant's work runs in its own scope, because row-level security binds a transaction to one tenant. The window
 * is deliberately short: a late event inside three hours changes the next report, and an older bucket is final once the
 * cursor passes its day. Retention reads that cursor, so a raw partition is dropped only after the rollups absorbed it.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RollupJob {

    private static final Logger LOG = LoggerFactory.getLogger(RollupJob.class);

    private final TenantSweep sweep;
    private final RunRollupUseCase rollup;
    private final Clock clock;

    RollupJob(TenantSweep sweep, RunRollupUseCase rollup, Clock clock) {
        this.sweep = sweep;
        this.rollup = rollup;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${attribution.rollups.interval:PT1M}")
    public void run() {
        Instant now = clock.now();
        sweep.forEachActiveTenant(tenantId -> {
            RunRollupUseCase.Result result = rollup.execute(now);
            LOG.info(
                    "tenant {}: rolled up {} traffic bucket(s), {} referrer row(s), {} conversion row(s), through {}",
                    tenantId,
                    result.trafficBuckets(),
                    result.referrers(),
                    result.conversions(),
                    result.rolledUpThrough());
        });
    }
}

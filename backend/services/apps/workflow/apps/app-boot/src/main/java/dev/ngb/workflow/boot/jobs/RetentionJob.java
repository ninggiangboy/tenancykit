package dev.ngb.workflow.boot.jobs;

import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.boot.config.RetentionSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges old history once per tenant, inside that tenant's scope. The sweep itself is bounded per round so a job cannot
 * hold a long transaction; the retention rule is that a finished instance and its rows expire, an open one never does.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private final TenantSweep sweep;
    private final RetentionSweep retention;
    private final Clock clock;

    RetentionJob(TenantSweep sweep, RetentionSweep retention, Clock clock) {
        this.sweep = sweep;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${workflow.housekeeping.instance-interval:PT1H}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            int purged = retention.purge(clock.now());
            if (purged > 0) {
                LOG.info("tenant {}: purged {} finished instance(s)", tenantId, purged);
            }
        });
    }
}

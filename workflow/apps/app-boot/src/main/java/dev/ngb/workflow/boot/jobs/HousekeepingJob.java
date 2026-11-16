package dev.ngb.workflow.boot.jobs;

import dev.ngb.workflow.boot.config.WorkflowSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded housekeeping: it purges consumed inbox rows and dead outbox rows, per tenant. Retention of workflow history
 * and inbound events joins it when those tables exist.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final TenantSweep sweep;
    private final MessagingHousekeeping messaging;
    private final WorkflowSettings settings;

    HousekeepingJob(TenantSweep sweep, MessagingHousekeeping messaging, WorkflowSettings settings) {
        this.sweep = sweep;
        this.messaging = messaging;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${workflow.housekeeping.interval:PT1H}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            MessagingHousekeeping.Purged purged = messaging.runOnce(tenantId);
            if (purged.inbox() > 0 || purged.outbox() > 0) {
                LOG.info(
                        "tenant {}: purged {} inbox row(s) and {} dead outbox row(s)",
                        tenantId, purged.inbox(), purged.outbox());
            }
        });
    }
}

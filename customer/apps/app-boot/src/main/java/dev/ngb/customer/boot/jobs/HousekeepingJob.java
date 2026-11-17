package dev.ngb.customer.boot.jobs;

import dev.ngb.core.runtime.Clock;
import dev.ngb.customer.boot.config.CustomerSettings;
import dev.ngb.customer.boot.infrastructure.lease.JobLeases;
import dev.ngb.customer.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges the inbox, the outbox rows whose life is over and the finished webhook deliveries, per tenant and in bounded
 * batches. It ships with the schema: without it the personal-data claim on delivery bodies and outbox payloads, and
 * the table growth, are both false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "customer.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);
    private static final String JOB = "housekeeping";

    private final TenantSweep sweep;
    private final MessagingHousekeeping messaging;
    private final PurgeDeliveriesUseCase deliveries;
    private final JobLeases leases;
    private final CustomerSettings settings;
    private final Clock clock;

    HousekeepingJob(
            TenantSweep sweep,
            MessagingHousekeeping messaging,
            PurgeDeliveriesUseCase deliveries,
            JobLeases leases,
            CustomerSettings settings,
            Clock clock) {
        this.sweep = sweep;
        this.messaging = messaging;
        this.deliveries = deliveries;
        this.leases = leases;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${customer.housekeeping.interval:PT1H}")
    public void run() {
        if (!leases.tryAcquire(JOB, settings.housekeeping().interval())) {
            LOG.debug("another instance holds the {} lease; skipping this round", JOB);
            return;
        }
        try {
            sweep.forEachActiveTenant(tenantId -> {
                int purged = deliveries.runOnce(clock.now());
                MessagingHousekeeping.Purged messagingPurged = messaging.runOnce(tenantId);
                if (purged > 0
                        || messagingPurged.inbox() > 0
                        || messagingPurged.relayed() > 0
                        || messagingPurged.dead() > 0) {
                    LOG.info(
                            "tenant {}: purged {} delivery(ies), {} inbox row(s), {} relayed outbox row(s), {} dead outbox row(s)",
                            tenantId, purged, messagingPurged.inbox(), messagingPurged.relayed(),
                            messagingPurged.dead());
                }
            });
        } finally {
            leases.release(JOB);
        }
    }
}

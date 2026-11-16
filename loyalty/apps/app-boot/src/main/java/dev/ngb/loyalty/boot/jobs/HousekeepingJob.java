package dev.ngb.loyalty.boot.jobs;

import dev.ngb.loyalty.boot.config.LoyaltySettings;
import dev.ngb.loyalty.webhook.application.delivery.command.PurgeDeliveriesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded retention, per tenant: the inbox, the dead outbox rows and the finished webhook deliveries with their attempt
 * logs. It ships with the schema — without it the personal-data claim and the table growth are both false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final TenantSweep sweep;
    private final MessagingHousekeeping messaging;
    private final PurgeDeliveriesUseCase purgedDeliveries;
    private final LoyaltySettings settings;

    HousekeepingJob(
            TenantSweep sweep,
            MessagingHousekeeping messaging,
            PurgeDeliveriesUseCase purgedDeliveries,
            LoyaltySettings settings) {
        this.sweep = sweep;
        this.messaging = messaging;
        this.purgedDeliveries = purgedDeliveries;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${loyalty.housekeeping.interval:PT1H}")
    public void run() {
        LoyaltySettings.Housekeeping limits = settings.housekeeping();
        sweep.forEachActiveTenant(tenantId -> {
            MessagingHousekeeping.Purged messagingPurged = messaging.runOnce(tenantId);
            int deliveries = purgedDeliveries.execute(limits.batchSize());
            if (messagingPurged.inbox() > 0 || messagingPurged.outbox() > 0 || deliveries > 0) {
                LOG.info(
                        "tenant {}: purged {} inbox row(s), {} dead outbox row(s), {} finished webhook deliver(ies)",
                        tenantId, messagingPurged.inbox(), messagingPurged.outbox(), deliveries);
            }
        });
    }
}

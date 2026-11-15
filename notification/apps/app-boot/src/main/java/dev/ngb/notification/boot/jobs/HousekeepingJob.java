package dev.ngb.notification.boot.jobs;

import dev.ngb.notification.dispatch.application.notification.command.HousekeepingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges or redacts rows past their retention, per tenant and in bounded batches.
 *
 * <p>It ships with the schema rather than later: without a purge the in-app bodies, the inbox, the dispatch ledger and
 * the dead outbox rows grow without bound, and the plan's claim about personal data is false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "notification.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final TenantSweep sweep;
    private final HousekeepingUseCase dispatchHousekeeping;
    private final MessagingHousekeeping messagingHousekeeping;

    HousekeepingJob(
            TenantSweep sweep,
            HousekeepingUseCase dispatchHousekeeping,
            MessagingHousekeeping messagingHousekeeping) {
        this.sweep = sweep;
        this.dispatchHousekeeping = dispatchHousekeeping;
        this.messagingHousekeeping = messagingHousekeeping;
    }

    @Scheduled(fixedDelayString = "${notification.housekeeping.interval:PT1H}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            HousekeepingUseCase.Purged dispatch = dispatchHousekeeping.runOnce(tenantId);
            MessagingHousekeeping.Purged messaging = messagingHousekeeping.runOnce(tenantId);
            if (dispatch.notifications() > 0 || dispatch.dispatches() > 0 || messaging.inbox() > 0 || messaging.outbox() > 0) {
                LOG.info(
                        "tenant {}: purged {} notification(s), redacted {} dispatch row(s), deleted {} inbox row(s) and {} dead outbox row(s)",
                        tenantId, dispatch.notifications(), dispatch.dispatches(), messaging.inbox(), messaging.outbox());
            }
        });
    }
}

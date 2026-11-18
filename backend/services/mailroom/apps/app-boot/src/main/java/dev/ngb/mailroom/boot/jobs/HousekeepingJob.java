package dev.ngb.mailroom.boot.jobs;

import dev.ngb.mailroom.boot.config.MailroomSettings;
import dev.ngb.mailroom.sending.application.housekeeping.command.HousekeepingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges rendered bodies, tracking links, old campaign snapshots, the inbox and the dead outbox, per tenant and in
 * bounded batches. It ships with the schema: without it the personal-data claim and the table growth are both false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailroom.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final TenantSweep sweep;
    private final HousekeepingUseCase housekeeping;
    private final MessagingHousekeeping messaging;
    private final MailroomSettings settings;

    HousekeepingJob(
            TenantSweep sweep,
            HousekeepingUseCase housekeeping,
            MessagingHousekeeping messaging,
            MailroomSettings settings) {
        this.sweep = sweep;
        this.housekeeping = housekeeping;
        this.messaging = messaging;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${email.housekeeping.interval:PT1H}")
    public void run() {
        MailroomSettings.Housekeeping limits = settings.housekeeping();
        sweep.forEachActiveTenant(tenantId -> {
            HousekeepingUseCase.Purged purged =
                    housekeeping.execute(limits.batchSize(), limits.campaignRecipientRetention());
            MessagingHousekeeping.Purged messagingPurged = messaging.runOnce(tenantId);
            if (purged.bodies() > 0 || purged.links() > 0 || purged.campaignRecipients() > 0
                    || messagingPurged.inbox() > 0 || messagingPurged.outbox() > 0) {
                LOG.info(
                        "tenant {}: purged {} body(ies), {} link(s), {} campaign snapshot row(s), {} inbox row(s), {} dead outbox row(s)",
                        tenantId, purged.bodies(), purged.links(), purged.campaignRecipients(),
                        messagingPurged.inbox(), messagingPurged.outbox());
            }
        });
    }
}

package dev.ngb.mailer.boot.jobs;

import dev.ngb.mailer.email.application.email.command.HousekeepingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges bodies and inbox rows past their retention, per tenant and in bounded batches.
 *
 * <p>It ships with the schema rather than later, because retention is what makes the body's short TTL a fact: without a
 * purge the tables grow without bound and the plan's claim about personal data is false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailer.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
public class HousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

    private final TenantSweep sweep;
    private final HousekeepingUseCase useCase;

    HousekeepingJob(TenantSweep sweep, HousekeepingUseCase useCase) {
        this.sweep = sweep;
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${mailer.housekeeping.interval:PT1H}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            HousekeepingUseCase.Purged purged = useCase.runOnce();
            if (purged.bodies() > 0 || purged.inbox() > 0) {
                LOG.info(
                        "tenant {}: purged {} body(ies) and {} inbox row(s)",
                        tenantId, purged.bodies(), purged.inbox());
            }
        });
    }
}

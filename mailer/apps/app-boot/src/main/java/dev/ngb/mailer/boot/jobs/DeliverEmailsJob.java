package dev.ngb.mailer.boot.jobs;

import dev.ngb.mailer.email.application.email.command.DeliverEmailsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One delivery round per poll, for every active tenant.
 *
 * <p>The job does nothing when {@code mailer.delivery.enabled} is false, which is how a deployment stops sending without
 * losing queued work: the rows stay {@code PENDING} and go out when it is switched back on.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailer.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class DeliverEmailsJob {

    private static final Logger LOG = LoggerFactory.getLogger(DeliverEmailsJob.class);

    private final TenantSweep sweep;
    private final DeliverEmailsUseCase useCase;

    DeliverEmailsJob(TenantSweep sweep, DeliverEmailsUseCase useCase) {
        this.sweep = sweep;
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${mailer.delivery.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            DeliverEmailsUseCase.Round round = useCase.runOnce();
            if (round.claimed() > 0) {
                // Counts and outcomes only: never a recipient, never a body.
                LOG.info(
                        "tenant {}: claimed {} delivery(ies), {} succeeded, {} retrying, {} dead",
                        tenantId, round.claimed(), round.succeeded(), round.retrying(), round.dead());
            }
        });
    }
}

package dev.ngb.customer.boot.jobs;

import dev.ngb.customer.boot.config.CustomerSettings;
import dev.ngb.customer.boot.infrastructure.lease.JobLeases;
import dev.ngb.customer.webhook.application.delivery.command.DeliverWebhooksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One round of the webhook sender per poll, for every active tenant: claim what is due, post it, record what came back.
 *
 * <p>The claim is {@code for update skip locked} inside a short transaction, so two workers never take the same
 * delivery; the HTTP calls happen outside any transaction and in parallel. {@link JobLeases} adds a lease per poll so a
 * second instance skips a round the first one is in the middle of, and a crashed worker's lease runs out instead of
 * blocking the job forever.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "customer.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDeliveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryJob.class);
    private static final String JOB = "webhook-delivery";

    private final TenantSweep sweep;
    private final DeliverWebhooksUseCase deliver;
    private final JobLeases leases;
    private final CustomerSettings settings;

    WebhookDeliveryJob(TenantSweep sweep, DeliverWebhooksUseCase deliver, JobLeases leases, CustomerSettings settings) {
        this.sweep = sweep;
        this.deliver = deliver;
        this.leases = leases;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${customer.relay.poll-delay:PT2S}")
    public void run() {
        if (!leases.tryAcquire(JOB, settings.relay().claimDeadline())) {
            LOG.debug("another instance holds the {} lease; skipping this round", JOB);
            return;
        }
        try {
            sweep.forEachActiveTenant(tenantId -> {
                DeliverWebhooksUseCase.Round round = deliver.runOnce();
                if (round.claimed() > 0) {
                    LOG.info(
                            "tenant {}: {} delivery(ies) claimed, {} succeeded, {} retrying, {} dead",
                            tenantId, round.claimed(), round.succeeded(), round.retrying(), round.dead());
                }
            });
        } finally {
            leases.release(JOB);
        }
    }
}

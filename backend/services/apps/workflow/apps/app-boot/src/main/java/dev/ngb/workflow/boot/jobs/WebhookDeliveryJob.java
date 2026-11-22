package dev.ngb.workflow.boot.jobs;

import dev.ngb.workflow.boot.config.WorkflowSettings;
import dev.ngb.workflow.webhook.application.delivery.command.DeliverWebhooksUseCase;
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
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDeliveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryJob.class);

    private final TenantSweep sweep;
    private final DeliverWebhooksUseCase deliver;
    private final WorkflowSettings settings;

    WebhookDeliveryJob(TenantSweep sweep, DeliverWebhooksUseCase deliver, WorkflowSettings settings) {
        this.sweep = sweep;
        this.deliver = deliver;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${workflow.relay.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            DeliverWebhooksUseCase.Round round = deliver.runOnce();
            if (round.claimed() > 0) {
                LOG.info(
                        "tenant {}: {} delivery(ies) claimed, {} succeeded, {} retrying, {} dead",
                        tenantId, round.claimed(), round.succeeded(), round.retrying(), round.dead());
            }
        });
    }
}

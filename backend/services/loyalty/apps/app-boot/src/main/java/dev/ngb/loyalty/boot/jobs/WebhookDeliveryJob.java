package dev.ngb.loyalty.boot.jobs;

import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One delivery round per poll, for every active tenant: claim what is due, sign it and send it, record what came back.
 *
 * <p>The worker owns delivery. The api runtime records a delivery inside the relay's transaction and never sends one,
 * so an api replica has no outbound connection to a Tenant's endpoint.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDeliveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryJob.class);

    private final TenantSweep sweep;
    private final DeliverWebhooksUseCase deliver;

    WebhookDeliveryJob(TenantSweep sweep, DeliverWebhooksUseCase deliver) {
        this.sweep = sweep;
        this.deliver = deliver;
    }

    @Scheduled(fixedDelayString = "${loyalty.webhooks.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            DeliverWebhooksUseCase.Round round = deliver.runOnce();
            if (round.claimed() > 0) {
                LOG.info(
                        "tenant {}: {} deliver(ies) claimed, {} succeeded, {} retrying, {} dead",
                        tenantId, round.claimed(), round.succeeded(), round.retrying(), round.dead());
            }
        });
    }
}

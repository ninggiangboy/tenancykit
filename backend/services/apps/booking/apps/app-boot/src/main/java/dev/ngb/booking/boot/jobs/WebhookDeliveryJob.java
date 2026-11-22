package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.booking.webhook.application.delivery.command.DeliverWebhooksUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One round of the webhook sender per poll, for every active tenant: claim what is due, post it, record what came back.
 *
 * <p>The claim is {@code for update skip locked} inside a short transaction, so two workers never take the same
 * delivery; the HTTP calls happen outside any transaction and in parallel. The job does nothing when
 * {@code booking.jobs.enabled} is false, which is what a test relies on.
 */
@Profile("worker")
@Component
public class WebhookDeliveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryJob.class);

    private final TenantSweep sweep;
    private final DeliverWebhooksUseCase deliver;
    private final BookingSettings settings;

    WebhookDeliveryJob(TenantSweep sweep, DeliverWebhooksUseCase deliver, BookingSettings settings) {
        this.sweep = sweep;
        this.deliver = deliver;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${booking.relay.poll-delay:PT2S}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
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

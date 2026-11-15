package dev.ngb.email.boot.jobs;

import dev.ngb.email.sending.application.campaign.command.PublishCampaignBatchUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes one rate-limited batch of every SENDING campaign, once per tenant. */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "email.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignPublisherJob {

    private static final Logger LOG = LoggerFactory.getLogger(CampaignPublisherJob.class);

    private final TenantSweep sweep;
    private final PublishCampaignBatchUseCase publish;

    CampaignPublisherJob(TenantSweep sweep, PublishCampaignBatchUseCase publish) {
        this.sweep = sweep;
        this.publish = publish;
    }

    @Scheduled(fixedDelayString = "${email.campaign.publisher-interval:PT5S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            PublishCampaignBatchUseCase.Published published = publish.execute();
            if (published.published() > 0 || published.failed() > 0 || published.skipped() > 0) {
                LOG.info(
                        "tenant {}: published {}, skipped {}, failed {}, completed {} campaign(s)",
                        tenantId, published.published(), published.skipped(), published.failed(), published.completed());
            }
        });
    }
}

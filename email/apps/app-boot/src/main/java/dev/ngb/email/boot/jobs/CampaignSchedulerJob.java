package dev.ngb.email.boot.jobs;

import dev.ngb.email.sending.application.campaign.command.AdvanceCampaignsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves due SCHEDULED campaigns to SENDING and snapshots their audience, once per tenant. */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "email.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignSchedulerJob {

    private static final Logger LOG = LoggerFactory.getLogger(CampaignSchedulerJob.class);
    private static final int MAX_PER_TICK = 50;

    private final TenantSweep sweep;
    private final AdvanceCampaignsUseCase advance;

    CampaignSchedulerJob(TenantSweep sweep, AdvanceCampaignsUseCase advance) {
        this.sweep = sweep;
        this.advance = advance;
    }

    @Scheduled(fixedDelayString = "${email.campaign.scheduler-interval:PT5S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            int started = advance.startDue(MAX_PER_TICK);
            if (started > 0) {
                LOG.info("tenant {}: started {} scheduled campaign(s)", tenantId, started);
            }
        });
    }
}

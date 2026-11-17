package dev.ngb.workflow.boot.jobs;

import dev.ngb.workflow.boot.config.WorkflowSettings;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.runtime.application.runtime.service.TokenSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net behind the wake-up queue: once per tenant it takes back expired leases and runs whatever is due.
 *
 * <p>Every claim is a {@code SKIP LOCKED} update, so several sweeps and several consumers may run at once without
 * coordinating, and a run that finds nothing is one cheap query.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RunnableSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(RunnableSweepJob.class);

    private final TenantSweep sweep;
    private final TokenSweeper sweeper;
    private final ClaimAndRun claimAndRun;
    private final WorkflowSettings settings;

    RunnableSweepJob(TenantSweep sweep, TokenSweeper sweeper, ClaimAndRun claimAndRun, WorkflowSettings settings) {
        this.sweep = sweep;
        this.sweeper = sweeper;
        this.claimAndRun = claimAndRun;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${workflow.runtime.sweep-interval:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            int requeued = sweeper.requeueExpired();
            int ran = claimAndRun.drain(settings.runtime().batch());
            if (requeued > 0 || ran > 0) {
                LOG.info("tenant {}: took back {} lease(s), ran {} step(s)", tenantId, requeued, ran);
            }
        });
    }
}

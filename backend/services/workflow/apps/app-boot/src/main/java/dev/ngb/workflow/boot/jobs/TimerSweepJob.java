package dev.ngb.workflow.boot.jobs;

import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.boot.config.WorkflowSettings;
import dev.ngb.workflow.runtime.application.runtime.service.TimerSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires due timers, once per tenant, inside that tenant's scope.
 *
 * <p>A deadline is claimed with {@code FOR UPDATE SKIP LOCKED} and marked fired conditionally; resuming the token is a
 * conditional WAITING -> READY update, so two workers sweeping together resume once. The sweep is the only way a delay
 * moves forward when no event will.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TimerSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(TimerSweepJob.class);

    private final TenantSweep sweep;
    private final TimerSweep timers;
    private final WorkflowSettings settings;
    private final Clock clock;

    TimerSweepJob(TenantSweep sweep, TimerSweep timers, WorkflowSettings settings, Clock clock) {
        this.sweep = sweep;
        this.timers = timers;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${workflow.runtime.sweep-interval:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            int fired = timers.fireDue(clock.now(), settings.runtime().batch());
            if (fired > 0) {
                LOG.info("tenant {}: fired {} timer(s)", tenantId, fired);
            }
        });
    }
}

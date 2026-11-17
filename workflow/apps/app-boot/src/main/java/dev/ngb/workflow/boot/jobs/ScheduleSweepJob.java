package dev.ngb.workflow.boot.jobs;

import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.boot.config.WorkflowSettings;
import dev.ngb.workflow.triggers.application.trigger.command.FireScheduleUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires due cron schedules, once per tenant, inside that tenant's scope.
 *
 * <p>A tick is claimed with {@code FOR UPDATE SKIP LOCKED} and its next due time advances before the instance starts, so
 * two workers never fire one tick and a tick missed while the worker was down fires once, not once per interval.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleSweepJob.class);

    private final TenantSweep sweep;
    private final FireScheduleUseCase schedules;
    private final WorkflowSettings settings;
    private final Clock clock;

    ScheduleSweepJob(
            TenantSweep sweep, FireScheduleUseCase schedules, WorkflowSettings settings, Clock clock) {
        this.sweep = sweep;
        this.schedules = schedules;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${workflow.schedules.interval:PT30S}")
    public void run() {
        if (!settings.schedules().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenantId -> {
            int fired = schedules.fireDue(clock.now(), settings.schedules().batch());
            if (fired > 0) {
                LOG.info("tenant {}: fired {} schedule(s)", tenantId, fired);
            }
        });
    }
}

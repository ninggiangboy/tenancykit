package dev.ngb.workflow.boot.jobs;

import dev.ngb.workflow.boot.config.WorkflowSettings;
import dev.ngb.workflow.tasks.application.task.service.TaskSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Escalates and expires tasks whose deadlines passed, once per tenant inside that tenant's scope. The due row is
 * re-read under a lock and moved by a conditional update, so two workers sweeping together move a task once.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TaskSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSweepJob.class);

    private final TenantSweep sweep;
    private final TaskSweep tasks;
    private final WorkflowSettings settings;

    TaskSweepJob(TenantSweep sweep, TaskSweep tasks, WorkflowSettings settings) {
        this.sweep = sweep;
        this.tasks = tasks;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${workflow.tasks.sweep-interval:PT30S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            int moved = tasks.runOnce(settings.runtime().batch());
            if (moved > 0) {
                LOG.info("tenant {}: escalated or expired {} task(s)", tenantId, moved);
            }
        });
    }
}

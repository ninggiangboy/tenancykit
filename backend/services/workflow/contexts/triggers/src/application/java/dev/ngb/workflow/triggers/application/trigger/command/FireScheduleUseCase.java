package dev.ngb.workflow.triggers.application.trigger.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.api.instance.StartRequest;
import dev.ngb.workflow.triggers.application.trigger.port.CronCalendar;
import dev.ngb.workflow.triggers.application.trigger.port.ScheduleRepository;
import dev.ngb.workflow.triggers.application.trigger.port.TriggerRepository;
import dev.ngb.workflow.triggers.domain.schedule.Schedule;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fires the schedules that are due, one transaction per sweep.
 *
 * <p>The due rows are claimed with {@code FOR UPDATE SKIP LOCKED} and {@code next_due} is advanced before the instance
 * starts, so two workers firing together produce one instance and a tick that fires twice changes nothing. A tick missed
 * while the worker was down fires once on the next sweep, not once per missed interval, because the next due time is
 * computed after now.
 */
public class FireScheduleUseCase {

    private final UnitOfWork unitOfWork;
    private final ScheduleRepository schedules;
    private final TriggerRepository triggers;
    private final VersionReader versions;
    private final InstanceStarter starter;
    private final CronCalendar calendar;

    public FireScheduleUseCase(
            UnitOfWork unitOfWork,
            ScheduleRepository schedules,
            TriggerRepository triggers,
            VersionReader versions,
            InstanceStarter starter,
            CronCalendar calendar) {
        this.unitOfWork = unitOfWork;
        this.schedules = schedules;
        this.triggers = triggers;
        this.versions = versions;
        this.starter = starter;
        this.calendar = calendar;
    }

    public int fireDue(Instant now, int limit) {
        return unitOfWork.transactional(() -> {
            List<Schedule> due = schedules.lockDue(now, limit);
            int fired = 0;
            for (Schedule schedule : due) {
                if (!calendar.valid(schedule.cron(), schedule.timezone())) {
                    continue;
                }
                Instant next = calendar.next(schedule.cron(), schedule.timezone(), now);
                Trigger trigger = triggers.findById(schedule.triggerId()).orElse(null);
                if (trigger == null || !trigger.enabled()) {
                    schedules.update(schedule.fired(next, schedule.lastFired()));
                    continue;
                }
                PublishedGraph version = versions.latest(trigger.definitionId()).orElse(null);
                // Advance first: a crash after this point fires the next tick once, never this one twice.
                schedules.update(schedule.fired(next, now));
                if (version == null) {
                    continue;
                }
                starter.start(new StartRequest(
                        version.versionId(),
                        Map.of("scheduledAt", now.toString()),
                        "schedule/" + schedule.triggerId(),
                        "schedule",
                        null,
                        null));
                fired++;
            }
            return fired;
        });
    }
}

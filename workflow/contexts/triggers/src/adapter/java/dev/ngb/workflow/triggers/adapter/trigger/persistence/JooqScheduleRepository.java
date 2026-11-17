package dev.ngb.workflow.triggers.adapter.trigger.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_SCHEDULE;

import dev.ngb.workflow.triggers.application.trigger.port.ScheduleRepository;
import dev.ngb.workflow.triggers.domain.schedule.Schedule;
import dev.ngb.workflow_jooq.tables.records.WorkflowScheduleRecord;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;

/** The schedules; a due tick is claimed with {@code FOR UPDATE SKIP LOCKED}, so two workers never fire it together. */
public class JooqScheduleRepository implements ScheduleRepository {

    private final DSLContext dsl;

    public JooqScheduleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Schedule schedule) {
        dsl.insertInto(WORKFLOW_SCHEDULE)
                .set(WORKFLOW_SCHEDULE.ID, schedule.id())
                .set(WORKFLOW_SCHEDULE.TENANT_ID, schedule.tenantId())
                .set(WORKFLOW_SCHEDULE.TRIGGER_ID, schedule.triggerId())
                .set(WORKFLOW_SCHEDULE.CRON, schedule.cron())
                .set(WORKFLOW_SCHEDULE.TIMEZONE, schedule.timezone())
                .set(WORKFLOW_SCHEDULE.NEXT_DUE, schedule.nextDue())
                .set(WORKFLOW_SCHEDULE.LAST_FIRED, schedule.lastFired())
                .set(WORKFLOW_SCHEDULE.VERSION, schedule.version())
                .execute();
    }

    @Override
    public List<Schedule> lockDue(Instant now, int limit) {
        return dsl.selectFrom(WORKFLOW_SCHEDULE)
                .where(WORKFLOW_SCHEDULE.NEXT_DUE.le(now))
                .orderBy(WORKFLOW_SCHEDULE.NEXT_DUE)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(row -> toDomain(row));
    }

    @Override
    public void update(Schedule schedule) {
        int updated = dsl.update(WORKFLOW_SCHEDULE)
                .set(WORKFLOW_SCHEDULE.NEXT_DUE, schedule.nextDue())
                .set(WORKFLOW_SCHEDULE.LAST_FIRED, schedule.lastFired())
                .set(WORKFLOW_SCHEDULE.VERSION, schedule.version())
                .where(WORKFLOW_SCHEDULE.ID.eq(schedule.id()))
                .and(WORKFLOW_SCHEDULE.VERSION.lt(schedule.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("schedule " + schedule.id() + " changed under this fire");
        }
    }

    private static Schedule toDomain(WorkflowScheduleRecord row) {
        return new Schedule(
                row.getId(),
                row.getTenantId(),
                row.getTriggerId(),
                row.getCron(),
                row.getTimezone(),
                row.getNextDue(),
                row.getLastFired(),
                row.getVersion());
    }
}

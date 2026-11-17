package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TIMER;

import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.domain.timer.Timer;
import dev.ngb.workflow.runtime.domain.timer.TimerKind;
import dev.ngb.workflow_jooq.tables.records.WorkflowTimerRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** The timer rows; the sweep claims due ones with {@code FOR UPDATE SKIP LOCKED}. */
public class JooqTimerRepository implements TimerRepository {

    private final DSLContext dsl;

    public JooqTimerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Timer timer) {
        dsl.insertInto(WORKFLOW_TIMER)
                .set(WORKFLOW_TIMER.ID, timer.id())
                .set(WORKFLOW_TIMER.TENANT_ID, timer.tenantId().value())
                .set(WORKFLOW_TIMER.INSTANCE_ID, timer.instanceId())
                .set(WORKFLOW_TIMER.TOKEN_ID, timer.tokenId())
                .set(WORKFLOW_TIMER.KIND, timer.kind().name())
                .set(WORKFLOW_TIMER.DUE_AT, timer.dueAt())
                .set(WORKFLOW_TIMER.CREATED_AT, timer.createdAt())
                .execute();
    }

    @Override
    public List<Timer> lockDue(Instant now, int limit) {
        return dsl.selectFrom(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.DUE_AT.le(now))
                .and(WORKFLOW_TIMER.FIRED_AT.isNull())
                .orderBy(WORKFLOW_TIMER.DUE_AT)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(row -> toDomain(row));
    }

    @Override
    public boolean markFired(UUID timerId, Instant now) {
        return dsl.update(WORKFLOW_TIMER)
                        .set(WORKFLOW_TIMER.FIRED_AT, now)
                        .where(WORKFLOW_TIMER.ID.eq(timerId))
                        .and(WORKFLOW_TIMER.FIRED_AT.isNull())
                        .execute()
                == 1;
    }

    @Override
    public int cancelForToken(UUID tokenId, Instant now) {
        return dsl.update(WORKFLOW_TIMER)
                .set(WORKFLOW_TIMER.FIRED_AT, now)
                .where(WORKFLOW_TIMER.TOKEN_ID.eq(tokenId))
                .and(WORKFLOW_TIMER.FIRED_AT.isNull())
                .execute();
    }

    private static Timer toDomain(WorkflowTimerRecord row) {
        return new Timer(
                row.getId(),
                new dev.ngb.core.id.TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getTokenId(),
                TimerKind.valueOf(row.getKind()),
                row.getDueAt(),
                row.getFiredAt(),
                row.getCreatedAt());
    }
}

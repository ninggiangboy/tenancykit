package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;

import dev.ngb.workflow.runtime.application.runtime.query.InstanceReadRepository;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** The history as the API reads it: one shaped view of this context's own rows, never a domain aggregate. */
public class JooqInstanceReadRepository implements InstanceReadRepository {

    private final DSLContext dsl;

    public JooqInstanceReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<InstanceView> list(
            UUID definitionId, String status, String correlationKey, int limit, int offset) {
        return fetch(condition(definitionId, status, correlationKey), limit, offset);
    }

    @Override
    public Optional<InstanceView> findById(UUID instanceId) {
        return fetch(condition(null, null, null).and(WORKFLOW_INSTANCE.ID.eq(instanceId)), null, null)
                .stream()
                .findFirst();
    }

    private Condition condition(UUID definitionId, String status, String correlationKey) {
        Condition condition = DSL.trueCondition();
        if (definitionId != null) {
            condition = condition.and(WORKFLOW_INSTANCE.DEFINITION_ID.eq(definitionId));
        }
        if (status != null && !status.isBlank()) {
            condition = condition.and(WORKFLOW_INSTANCE.STATUS.eq(status));
        }
        if (correlationKey != null && !correlationKey.isBlank()) {
            condition = condition.and(WORKFLOW_INSTANCE.CORRELATION_KEY.eq(correlationKey));
        }
        return condition;
    }

    private List<InstanceView> fetch(Condition condition, Integer limit, Integer offset) {
        Field<Integer> liveTokens = DSL.field(DSL.selectCount()
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(WORKFLOW_INSTANCE.ID))
                        .and(WORKFLOW_TOKEN.STATUS.in("READY", "RUNNING", "WAITING")))
                .as("live_tokens");
        Field<Integer> steps = DSL.field(DSL.selectCount()
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(WORKFLOW_INSTANCE.ID)))
                .as("steps");
        var query = dsl.select(
                        WORKFLOW_INSTANCE.ID,
                        WORKFLOW_INSTANCE.STATUS,
                        WORKFLOW_INSTANCE.DEFINITION_ID,
                        WORKFLOW_INSTANCE.VERSION_NUMBER,
                        WORKFLOW_INSTANCE.CORRELATION_KEY,
                        WORKFLOW_INSTANCE.FAILURE_REASON,
                        WORKFLOW_INSTANCE.STARTED_AT,
                        WORKFLOW_INSTANCE.FINISHED_AT,
                        liveTokens,
                        steps,
                        WORKFLOW_INSTANCE.VERSION)
                .from(WORKFLOW_INSTANCE)
                .where(condition)
                .orderBy(WORKFLOW_INSTANCE.STARTED_AT.desc());
        if (limit == null) {
            return query.fetch(this::toView);
        }
        return query.limit(limit).offset(offset == null ? 0 : offset).fetch(this::toView);
    }

    private InstanceView toView(Record row) {
        return new InstanceView(
                row.get(WORKFLOW_INSTANCE.ID),
                row.get(WORKFLOW_INSTANCE.STATUS),
                row.get(WORKFLOW_INSTANCE.DEFINITION_ID),
                null,
                row.get(WORKFLOW_INSTANCE.VERSION_NUMBER),
                row.get(WORKFLOW_INSTANCE.CORRELATION_KEY),
                row.get(WORKFLOW_INSTANCE.FAILURE_REASON),
                row.get(WORKFLOW_INSTANCE.STARTED_AT),
                row.get(WORKFLOW_INSTANCE.FINISHED_AT),
                row.get("live_tokens", Integer.class),
                row.get("steps", Integer.class),
                row.get(WORKFLOW_INSTANCE.VERSION));
    }
}

package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;

import dev.ngb.workflow.runtime.application.runtime.query.StepRunReadRepository;
import dev.ngb.workflow.runtime.application.runtime.query.StepRunView;
import dev.ngb.workflow_jooq.tables.records.WorkflowStepRunRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/** The step history as the API reads it: one shaped view per row, never a domain aggregate. */
public class JooqStepRunReadRepository implements StepRunReadRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqStepRunReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<StepRunView> timeline(UUID instanceId) {
        return dsl.selectFrom(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId))
                .orderBy(WORKFLOW_STEP_RUN.FINISHED_AT, WORKFLOW_STEP_RUN.ID)
                .fetch(this::toView);
    }

    private StepRunView toView(WorkflowStepRunRecord row) {
        return new StepRunView(
                row.getId(),
                row.getNodeId(),
                row.getAttempt(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getOutcome(),
                row.getErrorClass(),
                row.getFailure(),
                document(row.getResponse()),
                row.getIdempotencyKey());
    }

    private Object document(JSONB value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }
}

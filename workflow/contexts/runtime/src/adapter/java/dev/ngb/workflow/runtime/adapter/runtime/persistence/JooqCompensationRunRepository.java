package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_COMPENSATION_RUN;

import dev.ngb.workflow.runtime.application.runtime.port.CompensationRunRepository;
import dev.ngb.workflow.runtime.domain.compensation.CompensationRun;
import org.jooq.DSLContext;

/** The compensation rows: written when a compensation starts and moved to its end when it finishes. */
public class JooqCompensationRunRepository implements CompensationRunRepository {

    private final DSLContext dsl;

    public JooqCompensationRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(CompensationRun run) {
        dsl.insertInto(WORKFLOW_COMPENSATION_RUN)
                .set(WORKFLOW_COMPENSATION_RUN.ID, run.id())
                .set(WORKFLOW_COMPENSATION_RUN.TENANT_ID, run.tenantId().value())
                .set(WORKFLOW_COMPENSATION_RUN.INSTANCE_ID, run.instanceId())
                .set(WORKFLOW_COMPENSATION_RUN.STEP_RUN_ID, run.stepRunId())
                .set(WORKFLOW_COMPENSATION_RUN.NODE_ID, run.nodeId())
                .set(WORKFLOW_COMPENSATION_RUN.STATUS, run.status())
                .set(WORKFLOW_COMPENSATION_RUN.ATTEMPTS, run.attempts())
                .set(WORKFLOW_COMPENSATION_RUN.FAILURE, run.failure())
                .set(WORKFLOW_COMPENSATION_RUN.STARTED_AT, run.startedAt())
                .set(WORKFLOW_COMPENSATION_RUN.FINISHED_AT, run.finishedAt())
                .execute();
    }

    @Override
    public void update(CompensationRun run) {
        dsl.update(WORKFLOW_COMPENSATION_RUN)
                .set(WORKFLOW_COMPENSATION_RUN.STATUS, run.status())
                .set(WORKFLOW_COMPENSATION_RUN.ATTEMPTS, run.attempts())
                .set(WORKFLOW_COMPENSATION_RUN.FAILURE, run.failure())
                .set(WORKFLOW_COMPENSATION_RUN.FINISHED_AT, run.finishedAt())
                .where(WORKFLOW_COMPENSATION_RUN.ID.eq(run.id()))
                .and(WORKFLOW_COMPENSATION_RUN.STATUS.eq(CompensationRun.RUNNING))
                .execute();
    }
}

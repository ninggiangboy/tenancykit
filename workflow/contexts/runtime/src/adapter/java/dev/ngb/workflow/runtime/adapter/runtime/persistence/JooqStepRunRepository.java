package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;

import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.domain.step.StepRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record2;
import tools.jackson.databind.json.JsonMapper;

/** Appends one step row; the table refuses updates, so the history is the only story of a step. */
public class JooqStepRunRepository implements StepRunRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqStepRunRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(StepRun run) {
        dsl.insertInto(WORKFLOW_STEP_RUN)
                .set(WORKFLOW_STEP_RUN.ID, run.id())
                .set(WORKFLOW_STEP_RUN.TENANT_ID, run.tenantId().value())
                .set(WORKFLOW_STEP_RUN.INSTANCE_ID, run.instanceId())
                .set(WORKFLOW_STEP_RUN.TOKEN_ID, run.tokenId())
                .set(WORKFLOW_STEP_RUN.NODE_ID, run.nodeId())
                .set(WORKFLOW_STEP_RUN.ATTEMPT, run.attempt())
                .set(WORKFLOW_STEP_RUN.STARTED_AT, run.startedAt())
                .set(WORKFLOW_STEP_RUN.FINISHED_AT, run.finishedAt())
                .set(WORKFLOW_STEP_RUN.OUTCOME, run.outcome())
                .set(WORKFLOW_STEP_RUN.ERROR_CLASS, run.errorClass())
                .set(WORKFLOW_STEP_RUN.FAILURE, run.failure())
                .set(WORKFLOW_STEP_RUN.REQUEST, jsonb(run.request()))
                .set(WORKFLOW_STEP_RUN.RESPONSE, jsonb(run.response()))
                .set(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY, run.idempotencyKey())
                .execute();
    }

    @Override
    public long count(UUID instanceId) {
        return dsl.fetchCount(WORKFLOW_STEP_RUN, WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId));
    }

    @Override
    public Map<String, Object> outputs(UUID instanceId) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Record2<String, JSONB> row : dsl.select(WORKFLOW_STEP_RUN.NODE_ID, WORKFLOW_STEP_RUN.RESPONSE)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_STEP_RUN.OUTCOME.eq("SUCCEEDED"))
                .and(WORKFLOW_STEP_RUN.RESPONSE.isNotNull())
                .orderBy(WORKFLOW_STEP_RUN.NODE_ID, WORKFLOW_STEP_RUN.FINISHED_AT.desc())
                .fetch()) {
            outputs.putIfAbsent(row.value1(), json.readValue(row.value2().data(), Object.class));
        }
        return outputs;
    }

    @Override
    public List<String> completedNodeIds(UUID instanceId) {
        List<String> finished = dsl.select(WORKFLOW_STEP_RUN.NODE_ID)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_STEP_RUN.OUTCOME.eq("SUCCEEDED"))
                .orderBy(WORKFLOW_STEP_RUN.FINISHED_AT, WORKFLOW_STEP_RUN.NODE_ID)
                .fetch(WORKFLOW_STEP_RUN.NODE_ID);
        Map<String, String> latest = new LinkedHashMap<>();
        for (String nodeId : finished) {
            latest.remove(nodeId);
            latest.put(nodeId, nodeId);
        }
        return List.copyOf(latest.keySet());
    }

    @Override
    public Optional<UUID> latestStepId(UUID instanceId, String nodeId) {
        return dsl.select(WORKFLOW_STEP_RUN.ID)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_STEP_RUN.NODE_ID.eq(nodeId))
                .and(WORKFLOW_STEP_RUN.OUTCOME.eq("SUCCEEDED"))
                .orderBy(WORKFLOW_STEP_RUN.FINISHED_AT.desc())
                .limit(1)
                .fetchOptional(WORKFLOW_STEP_RUN.ID);
    }

    private JSONB jsonb(Object document) {
        return document == null ? null : JSONB.valueOf(json.writeValueAsString(document));
    }
}

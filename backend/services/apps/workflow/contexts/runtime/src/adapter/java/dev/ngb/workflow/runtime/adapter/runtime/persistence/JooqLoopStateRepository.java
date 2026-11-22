package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_LOOP_STATE;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.LoopStateRepository;
import dev.ngb.workflow_jooq.tables.records.WorkflowLoopStateRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The loop's progress row. The first arrival writes it; every body arrival locks it, records the output and the next
 * index, and releases it, so two branches that come back at once cannot start the same item.
 */
public class JooqLoopStateRepository implements LoopStateRepository {

    private static final TypeReference<List<Object>> ITEMS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MEMBERS = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqLoopStateRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Start start(
            TenantId tenantId, UUID instanceId, String nodeId, List<Object> items, int concurrency, Instant now) {
        String itemsJson = json.writeValueAsString(items);
        dsl.insertInto(WORKFLOW_LOOP_STATE)
                .set(WORKFLOW_LOOP_STATE.INSTANCE_ID, instanceId)
                .set(WORKFLOW_LOOP_STATE.NODE_ID, nodeId)
                .set(WORKFLOW_LOOP_STATE.TENANT_ID, tenantId.value())
                .set(WORKFLOW_LOOP_STATE.TOTAL, items.size())
                .set(WORKFLOW_LOOP_STATE.STARTED, 0)
                .set(WORKFLOW_LOOP_STATE.ARRIVED, 0)
                .set(WORKFLOW_LOOP_STATE.ITEMS, JSONB.valueOf(itemsJson))
                .set(WORKFLOW_LOOP_STATE.UPDATED_AT, now)
                .execute();
        int start = Math.min(items.size(), Math.max(1, concurrency));
        if (start > 0) {
            dsl.update(WORKFLOW_LOOP_STATE)
                    .set(WORKFLOW_LOOP_STATE.STARTED, start)
                    .set(WORKFLOW_LOOP_STATE.UPDATED_AT, now)
                    .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instanceId))
                    .and(WORKFLOW_LOOP_STATE.NODE_ID.eq(nodeId))
                    .execute();
        }
        List<Iteration> toStart = new ArrayList<>(start);
        for (int index = 0; index < start; index++) {
            toStart.add(new Iteration(index, items.get(index)));
        }
        return new Start(items.size(), List.copyOf(toStart));
    }

    @Override
    public Advance advance(
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            int iteration,
            Object output,
            Object error,
            boolean startNext,
            Instant now) {
        WorkflowLoopStateRecord row = dsl.selectFrom(WORKFLOW_LOOP_STATE)
                .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_LOOP_STATE.NODE_ID.eq(nodeId))
                .forUpdate()
                .fetchOne();
        if (row == null) {
            throw new IllegalStateException("a for-each body came back to a loop that never started");
        }
        List<Object> items = json.readValue(row.getItems().data(), ITEMS);
        Map<String, Object> outputs = new LinkedHashMap<>(json.readValue(row.getOutputs().data(), MEMBERS));
        Map<String, Object> errors = new LinkedHashMap<>(json.readValue(row.getErrors().data(), MEMBERS));
        if (output != null) {
            outputs.put(String.valueOf(iteration), output);
        }
        if (error != null) {
            errors.put(String.valueOf(iteration), error);
        }
        int started = row.getStarted();
        List<Iteration> toStart = new ArrayList<>();
        if (startNext && started < row.getTotal()) {
            toStart.add(new Iteration(started, items.get(started)));
            started++;
        }
        int arrived = row.getArrived() + 1;
        dsl.update(WORKFLOW_LOOP_STATE)
                .set(WORKFLOW_LOOP_STATE.STARTED, started)
                .set(WORKFLOW_LOOP_STATE.ARRIVED, arrived)
                .set(WORKFLOW_LOOP_STATE.OUTPUTS, JSONB.valueOf(json.writeValueAsString(outputs)))
                .set(WORKFLOW_LOOP_STATE.ERRORS, JSONB.valueOf(json.writeValueAsString(errors)))
                .set(WORKFLOW_LOOP_STATE.UPDATED_AT, now)
                .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_LOOP_STATE.NODE_ID.eq(nodeId))
                .execute();
        return new Advance(List.copyOf(toStart), arrived >= row.getTotal(), outputs, errors);
    }
}

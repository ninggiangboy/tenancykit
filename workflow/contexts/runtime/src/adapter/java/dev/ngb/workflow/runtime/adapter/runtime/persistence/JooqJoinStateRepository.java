package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_JOIN_STATE;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.JoinStateRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The join counter. The increment is a single upsert, so two branches that arrive at the same instant are serialised by
 * the row and neither loses a count; the output merge follows under the same transaction.
 */
public class JooqJoinStateRepository implements JoinStateRepository {

    private static final TypeReference<Map<String, Object>> OUTPUTS = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqJoinStateRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Arrival arrive(
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            int expected,
            String branchKey,
            Object contribution,
            Instant now) {
        int arrived = dsl.insertInto(WORKFLOW_JOIN_STATE)
                .set(WORKFLOW_JOIN_STATE.INSTANCE_ID, instanceId)
                .set(WORKFLOW_JOIN_STATE.NODE_ID, nodeId)
                .set(WORKFLOW_JOIN_STATE.TENANT_ID, tenantId.value())
                .set(WORKFLOW_JOIN_STATE.EXPECTED, expected)
                .set(WORKFLOW_JOIN_STATE.ARRIVED, 1)
                .set(WORKFLOW_JOIN_STATE.OUTPUTS, JSONB.valueOf(contributionJson(branchKey, contribution)))
                .set(WORKFLOW_JOIN_STATE.UPDATED_AT, now)
                .onConflict(WORKFLOW_JOIN_STATE.INSTANCE_ID, WORKFLOW_JOIN_STATE.NODE_ID)
                .doUpdate()
                .set(WORKFLOW_JOIN_STATE.ARRIVED, WORKFLOW_JOIN_STATE.ARRIVED.plus(1))
                .set(WORKFLOW_JOIN_STATE.UPDATED_AT, now)
                .returning(WORKFLOW_JOIN_STATE.ARRIVED)
                .fetchOne(WORKFLOW_JOIN_STATE.ARRIVED);

        JSONB collected = dsl.select(WORKFLOW_JOIN_STATE.OUTPUTS)
                .from(WORKFLOW_JOIN_STATE)
                .where(WORKFLOW_JOIN_STATE.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_JOIN_STATE.NODE_ID.eq(nodeId))
                .fetchOne(WORKFLOW_JOIN_STATE.OUTPUTS);
        Map<String, Object> outputs = new LinkedHashMap<>(collected == null ? Map.of() : json.readValue(collected.data(), OUTPUTS));
        String key = branchKey == null || branchKey.isBlank() ? "branch" : branchKey;
        outputs.put(key, contribution == null ? Map.of() : contribution);
        dsl.update(WORKFLOW_JOIN_STATE)
                .set(WORKFLOW_JOIN_STATE.OUTPUTS, JSONB.valueOf(json.writeValueAsString(outputs)))
                .set(WORKFLOW_JOIN_STATE.UPDATED_AT, now)
                .where(WORKFLOW_JOIN_STATE.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_JOIN_STATE.NODE_ID.eq(nodeId))
                .execute();
        return new Arrival(arrived, expected, outputs);
    }

    private String contributionJson(String branchKey, Object contribution) {
        String key = branchKey == null || branchKey.isBlank() ? "branch" : branchKey;
        return json.writeValueAsString(Map.of(key, contribution == null ? Map.of() : contribution));
    }
}

package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow_jooq.tables.records.WorkflowInstanceRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The instance row: its input, its variables and its optimistic version. */
public class JooqInstanceRepository implements InstanceRepository {

    private static final TypeReference<Map<String, Object>> VARIABLES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqInstanceRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Instance instance) {
        dsl.insertInto(WORKFLOW_INSTANCE)
                .set(WORKFLOW_INSTANCE.ID, instance.id())
                .set(WORKFLOW_INSTANCE.TENANT_ID, instance.tenantId().value())
                .set(WORKFLOW_INSTANCE.DEFINITION_ID, instance.definitionId())
                .set(WORKFLOW_INSTANCE.VERSION_ID, instance.versionId())
                .set(WORKFLOW_INSTANCE.VERSION_NUMBER, instance.versionNumber())
                .set(WORKFLOW_INSTANCE.STATUS, instance.status().name())
                .set(WORKFLOW_INSTANCE.INPUT, instance.input() == null
                        ? null
                        : JSONB.valueOf(json.writeValueAsString(instance.input())))
                .set(WORKFLOW_INSTANCE.VARIABLES, JSONB.valueOf(json.writeValueAsString(instance.variables())))
                .set(WORKFLOW_INSTANCE.CORRELATION_KEY, instance.correlationKey())
                .set(WORKFLOW_INSTANCE.STARTED_BY, instance.startedBy())
                .set(WORKFLOW_INSTANCE.STARTED_AT, instance.startedAt())
                .set(WORKFLOW_INSTANCE.FINISHED_AT, instance.finishedAt())
                .set(WORKFLOW_INSTANCE.VERSION, instance.version())
                .execute();
    }

    @Override
    public Optional<Instance> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(id))
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public long updateVariables(UUID id, Map<String, Object> variables, long expectedVersion) {
        int updated = dsl.update(WORKFLOW_INSTANCE)
                .set(WORKFLOW_INSTANCE.VARIABLES, JSONB.valueOf(json.writeValueAsString(variables)))
                .set(WORKFLOW_INSTANCE.VERSION, expectedVersion + 1)
                .where(WORKFLOW_INSTANCE.ID.eq(id))
                .and(WORKFLOW_INSTANCE.VERSION.eq(expectedVersion))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("instance " + id + " changed its variables under this step");
        }
        return expectedVersion + 1;
    }

    @Override
    public void finish(UUID id, InstanceStatus status, Instant finishedAt, long expectedVersion) {
        int updated = dsl.update(WORKFLOW_INSTANCE)
                .set(WORKFLOW_INSTANCE.STATUS, status.name())
                .set(WORKFLOW_INSTANCE.FINISHED_AT, finishedAt)
                .set(WORKFLOW_INSTANCE.VERSION, expectedVersion + 1)
                .where(WORKFLOW_INSTANCE.ID.eq(id))
                .and(WORKFLOW_INSTANCE.VERSION.eq(expectedVersion))
                .and(WORKFLOW_INSTANCE.FINISHED_AT.isNull())
                .execute();
        if (updated != 1) {
            // Another token of the same instance may have finished it first; that is the same terminal outcome.
            Instance current = findById(id).orElseThrow(() -> new IllegalStateException("instance " + id + " is gone"));
            if (!current.status().terminal()) {
                throw new IllegalStateException("instance " + id + " was finished by another writer");
            }
        }
    }

    private Instance toDomain(WorkflowInstanceRecord row) {
        JSONB input = row.getInput();
        return new Instance(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getDefinitionId(),
                row.getVersionId(),
                row.getVersionNumber(),
                InstanceStatus.valueOf(row.getStatus()),
                input == null ? null : json.readValue(input.data(), Object.class),
                json.readValue(row.getVariables().data(), VARIABLES),
                row.getCorrelationKey(),
                row.getStartedBy(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getVersion());
    }
}

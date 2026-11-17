package dev.ngb.workflow.triggers.adapter.trigger.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TRIGGER;

import dev.ngb.workflow.triggers.application.trigger.port.TriggerRepository;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import dev.ngb.workflow.triggers.domain.trigger.TriggerKind;
import dev.ngb.workflow_jooq.tables.records.WorkflowTriggerRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The trigger rows, with their expressions as the documents the tenant wrote. */
public class JooqTriggerRepository implements TriggerRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqTriggerRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Trigger trigger) {
        dsl.insertInto(WORKFLOW_TRIGGER)
                .set(WORKFLOW_TRIGGER.ID, trigger.id())
                .set(WORKFLOW_TRIGGER.TENANT_ID, trigger.tenantId())
                .set(WORKFLOW_TRIGGER.NAME, trigger.name())
                .set(WORKFLOW_TRIGGER.KIND, trigger.kind().name())
                .set(WORKFLOW_TRIGGER.SOURCE, trigger.source())
                .set(WORKFLOW_TRIGGER.EVENT_TYPE, trigger.eventType())
                .set(WORKFLOW_TRIGGER.DEFINITION_ID, trigger.definitionId())
                .set(WORKFLOW_TRIGGER.FILTER, jsonb(trigger.filter()))
                .set(WORKFLOW_TRIGGER.CORRELATION, jsonb(trigger.correlation()))
                .set(WORKFLOW_TRIGGER.INPUT, jsonb(trigger.input()))
                .set(WORKFLOW_TRIGGER.SECRET_CIPHERTEXT, trigger.secretCiphertext())
                .set(WORKFLOW_TRIGGER.KEY_ID, trigger.keyId())
                .set(WORKFLOW_TRIGGER.ENABLED, trigger.enabled())
                .set(WORKFLOW_TRIGGER.CREATED_AT, trigger.createdAt())
                .set(WORKFLOW_TRIGGER.UPDATED_AT, trigger.updatedAt())
                .set(WORKFLOW_TRIGGER.VERSION, trigger.version())
                .execute();
    }

    @Override
    public void update(Trigger trigger) {
        int updated = dsl.update(WORKFLOW_TRIGGER)
                .set(WORKFLOW_TRIGGER.NAME, trigger.name())
                .set(WORKFLOW_TRIGGER.EVENT_TYPE, trigger.eventType())
                .set(WORKFLOW_TRIGGER.FILTER, jsonb(trigger.filter()))
                .set(WORKFLOW_TRIGGER.CORRELATION, jsonb(trigger.correlation()))
                .set(WORKFLOW_TRIGGER.INPUT, jsonb(trigger.input()))
                .set(WORKFLOW_TRIGGER.ENABLED, trigger.enabled())
                .set(WORKFLOW_TRIGGER.UPDATED_AT, trigger.updatedAt())
                .set(WORKFLOW_TRIGGER.VERSION, trigger.version())
                .where(WORKFLOW_TRIGGER.ID.eq(trigger.id()))
                .and(WORKFLOW_TRIGGER.VERSION.lt(trigger.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("trigger " + trigger.id() + " changed under this write");
        }
    }

    @Override
    public Optional<Trigger> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_TRIGGER)
                .where(WORKFLOW_TRIGGER.ID.eq(id))
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public Optional<Trigger> lockById(UUID id) {
        return dsl.selectFrom(WORKFLOW_TRIGGER)
                .where(WORKFLOW_TRIGGER.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public List<Trigger> enabledForSource(String source, String eventType) {
        return dsl.selectFrom(WORKFLOW_TRIGGER)
                .where(WORKFLOW_TRIGGER.SOURCE.eq(source))
                .and(WORKFLOW_TRIGGER.ENABLED.isTrue())
                .and(WORKFLOW_TRIGGER.EVENT_TYPE.isNull().or(WORKFLOW_TRIGGER.EVENT_TYPE.eq(eventType)))
                .orderBy(WORKFLOW_TRIGGER.CREATED_AT)
                .fetch(row -> toDomain(row));
    }

    private JSONB jsonb(Object document) {
        return document == null ? null : JSONB.valueOf(json.writeValueAsString(document));
    }

    private Object document(JSONB value) {
        return value == null ? null : json.readValue(value.data(), DOCUMENT);
    }

    private Trigger toDomain(WorkflowTriggerRecord row) {
        return new Trigger(
                row.getId(),
                row.getTenantId(),
                row.getName(),
                TriggerKind.valueOf(row.getKind()),
                row.getSource(),
                row.getEventType(),
                row.getDefinitionId(),
                document(row.getFilter()),
                document(row.getCorrelation()),
                document(row.getInput()),
                row.getSecretCiphertext(),
                row.getKeyId(),
                row.getEnabled(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}

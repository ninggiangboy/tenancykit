package dev.ngb.workflow.definitions.adapter.definition.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_DEFINITION;

import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.DefinitionStatus;
import dev.ngb.workflow_jooq.tables.records.WorkflowDefinitionRecord;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The definition row: the draft as the tenant sent it, and the optimistic version an editor pins. */
public class JooqDefinitionRepository implements DefinitionRepository {

    private static final TypeReference<Map<String, Object>> GRAPH = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqDefinitionRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Definition definition) {
        dsl.insertInto(WORKFLOW_DEFINITION)
                .set(WORKFLOW_DEFINITION.ID, definition.id())
                .set(WORKFLOW_DEFINITION.TENANT_ID, definition.tenantId().value())
                .set(WORKFLOW_DEFINITION.CODE, definition.code())
                .set(WORKFLOW_DEFINITION.NAME, definition.name())
                .set(WORKFLOW_DEFINITION.STATUS, definition.status().name())
                .set(WORKFLOW_DEFINITION.DRAFT, draft(definition.draft()))
                .set(WORKFLOW_DEFINITION.LATEST_VERSION, definition.latestVersion())
                .set(WORKFLOW_DEFINITION.CREATED_AT, definition.createdAt())
                .set(WORKFLOW_DEFINITION.UPDATED_AT, definition.updatedAt())
                .set(WORKFLOW_DEFINITION.VERSION, definition.version())
                .execute();
    }

    @Override
    public void update(Definition definition) {
        int updated = dsl.update(WORKFLOW_DEFINITION)
                .set(WORKFLOW_DEFINITION.NAME, definition.name())
                .set(WORKFLOW_DEFINITION.STATUS, definition.status().name())
                .set(WORKFLOW_DEFINITION.DRAFT, draft(definition.draft()))
                .set(WORKFLOW_DEFINITION.LATEST_VERSION, definition.latestVersion())
                .set(WORKFLOW_DEFINITION.UPDATED_AT, definition.updatedAt())
                .set(WORKFLOW_DEFINITION.VERSION, definition.version())
                .where(WORKFLOW_DEFINITION.ID.eq(definition.id()))
                .and(WORKFLOW_DEFINITION.VERSION.lt(definition.version()))
                .execute();
        if (updated != 1) {
            long current = currentVersion(definition.id());
            throw new PreconditionFailedException(current);
        }
    }

    @Override
    public Optional<Definition> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.ID.eq(id))
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public Optional<Definition> lockById(UUID id) {
        return dsl.selectFrom(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public Optional<Definition> findByCode(String code) {
        return dsl.selectFrom(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.CODE.equalIgnoreCase(code))
                .fetchOptional(row -> toDomain(row));
    }

    private long currentVersion(UUID id) {
        return dsl.select(WORKFLOW_DEFINITION.VERSION)
                .from(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.ID.eq(id))
                .fetchOptional(WORKFLOW_DEFINITION.VERSION)
                .orElse(0L);
    }

    private JSONB draft(Object document) {
        return document == null ? null : JSONB.valueOf(json.writeValueAsString(document));
    }

    private Definition toDomain(WorkflowDefinitionRecord row) {
        JSONB draft = row.getDraft();
        return new Definition(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getCode(),
                row.getName(),
                DefinitionStatus.valueOf(row.getStatus()),
                draft == null ? null : json.readValue(draft.data(), GRAPH),
                row.getLatestVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}

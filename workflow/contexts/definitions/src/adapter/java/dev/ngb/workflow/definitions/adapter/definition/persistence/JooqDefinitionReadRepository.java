package dev.ngb.workflow.definitions.adapter.definition.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_DEFINITION;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_VERSION;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionListing;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionReadRepository;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionView;
import dev.ngb.workflow.definitions.application.definition.query.VersionView;
import dev.ngb.workflow.definitions.domain.definition.DefinitionStatus;
import dev.ngb.workflow_jooq.tables.records.WorkflowDefinitionRecord;
import dev.ngb.workflow_jooq.tables.records.WorkflowVersionRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Selects definitions straight into the views; the domain aggregate is never built on a read. */
public class JooqDefinitionReadRepository implements DefinitionReadRepository {

    /**
     * The graph is read back as a document. A read view names it {@code Object} because a version is the bytes that
     * were published, not a shape this context reinterprets.
     */
    private static final TypeReference<Map<String, Object>> GRAPH = new TypeReference<>() {};

    private static final ListBinding BINDING = ListBinding.of(DefinitionListing.SCHEMA)
            .column("id", WORKFLOW_DEFINITION.ID)
            .column("code", WORKFLOW_DEFINITION.CODE)
            .column("name", WORKFLOW_DEFINITION.NAME)
            .column("status", WORKFLOW_DEFINITION.STATUS)
            .column("latest_version", WORKFLOW_DEFINITION.LATEST_VERSION)
            .column("created_at", WORKFLOW_DEFINITION.CREATED_AT)
            .column("updated_at", WORKFLOW_DEFINITION.UPDATED_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    /** @return the binding of the definition listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqDefinitionReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<DefinitionView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, WORKFLOW_DEFINITION, org.jooq.impl.DSL.noCondition(), BINDING, query,
                JooqDefinitionReadRepository::toView);
    }

    @Override
    public Optional<DefinitionView> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.ID.eq(id))
                .fetchOptional(JooqDefinitionReadRepository::toView);
    }

    @Override
    public Optional<VersionView> findVersion(UUID definitionId, int number) {
        return dsl.selectFrom(WORKFLOW_VERSION)
                .where(WORKFLOW_VERSION.DEFINITION_ID.eq(definitionId))
                .and(WORKFLOW_VERSION.VERSION.eq(number))
                .fetchOptional(row -> toVersionView(row));
    }

    @Override
    public List<VersionView> versions(UUID definitionId) {
        return dsl.selectFrom(WORKFLOW_VERSION)
                .where(WORKFLOW_VERSION.DEFINITION_ID.eq(definitionId))
                .orderBy(WORKFLOW_VERSION.VERSION.desc())
                .fetch(row -> toVersionView(row));
    }

    private static DefinitionView toView(WorkflowDefinitionRecord row) {
        return new DefinitionView(
                row.getId(),
                row.getCode(),
                row.getName(),
                DefinitionStatus.valueOf(row.getStatus()),
                row.getLatestVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private VersionView toVersionView(WorkflowVersionRecord row) {
        JSONB graph = row.getGraph();
        return new VersionView(
                row.getId(),
                row.getDefinitionId(),
                row.getVersion(),
                json.readValue(graph.data(), GRAPH),
                row.getContentHash(),
                row.getPublishedAt(),
                row.getPublishedBy());
    }
}

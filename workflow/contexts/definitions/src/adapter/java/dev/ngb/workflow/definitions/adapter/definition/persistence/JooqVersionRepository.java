package dev.ngb.workflow.definitions.adapter.definition.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_VERSION;

import dev.ngb.workflow.definitions.application.definition.port.VersionRepository;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/** The version store inserts and never updates; the table's grant enforces the same rule. */
public class JooqVersionRepository implements VersionRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqVersionRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(PublishedVersion version) {
        dsl.insertInto(WORKFLOW_VERSION)
                .set(WORKFLOW_VERSION.ID, version.id())
                .set(WORKFLOW_VERSION.TENANT_ID, version.tenantId().value())
                .set(WORKFLOW_VERSION.DEFINITION_ID, version.definitionId())
                .set(WORKFLOW_VERSION.VERSION, version.number())
                .set(WORKFLOW_VERSION.GRAPH, JSONB.valueOf(json.writeValueAsString(version.graph())))
                .set(WORKFLOW_VERSION.CONTENT_HASH, version.contentHash())
                .set(WORKFLOW_VERSION.PUBLISHED_AT, version.publishedAt())
                .set(WORKFLOW_VERSION.PUBLISHED_BY, version.publishedBy())
                .execute();
    }
}

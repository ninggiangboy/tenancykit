package dev.ngb.workflow.definitions.adapter.version.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_VERSION;

import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.application.version.port.VersionGraphRows;
import dev.ngb.workflow.definitions.domain.definition.GraphParser;
import dev.ngb.workflow_jooq.tables.records.WorkflowVersionRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads the pinned version's graph and hands the runtime a plain view of it. */
public class JooqVersionReader implements VersionGraphRows {

    private static final TypeReference<Map<String, Object>> GRAPH = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqVersionReader(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Optional<PublishedGraph> graph(UUID versionId) {
        return dsl.selectFrom(WORKFLOW_VERSION)
                .where(WORKFLOW_VERSION.ID.eq(versionId))
                .fetchOptional()
                .flatMap(this::view);
    }

    private Optional<PublishedGraph> view(WorkflowVersionRecord row) {
        JSONB document = row.getGraph();
        GraphParser.Result parsed = GraphParser.parse(json.readValue(document.data(), GRAPH));
        if (parsed.graph() == null) {
            return Optional.empty();
        }
        List<GraphNode> nodes = parsed.graph().nodes().stream()
                .map(node -> new GraphNode(node.id(), node.type().wireName(), node.settings()))
                .toList();
        return Optional.of(new PublishedGraph(
                row.getId(),
                row.getDefinitionId(),
                row.getVersion(),
                new GraphView(parsed.graph().start(), nodes)));
    }
}

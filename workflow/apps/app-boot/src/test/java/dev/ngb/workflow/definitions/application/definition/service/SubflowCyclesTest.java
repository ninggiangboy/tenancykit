package dev.ngb.workflow.definitions.application.definition.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.domain.definition.GraphParser;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** A sub-workflow chain that returns to a definition already on its path is refused when it is published. */
class SubflowCyclesTest {

    @Test
    void aDirectSelfReferenceIsRefused() {
        List<Problem> problems = problems(chain("a", "a"), "a", Map.of());

        assertThat(describe(problems)).containsExactly("/nodes/1/definition subflow_cycle");
    }

    @Test
    void aCycleThroughAnotherDefinitionIsRefused() {
        List<Problem> problems = problems(chain("b", "a"), "a", Map.of("b", chain("a", "b")));

        assertThat(describe(problems)).containsExactly("/nodes/1/definition subflow_cycle");
    }

    @Test
    void aChainThatEndsIsAccepted() {
        List<Problem> problems = problems(chain("b", "a"), "a", Map.of("b", chain(null, "b")));

        assertThat(problems).isEmpty();
    }

    @Test
    void anUnpublishedReferenceIsNotAPublishTimeCycle() {
        List<Problem> problems = problems(chain("missing", "a"), "a", Map.of());

        assertThat(problems).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static List<Problem> problems(
            Object draft, String ownCode, Map<String, Object> publishedByCode) {
        GraphParser.Result parsed = GraphParser.parse(draft);
        Function<String, Optional<PublishedGraph>> catalog = code -> Optional.ofNullable(publishedByCode.get(code))
                .map(graph -> {
                    GraphParser.Result target = GraphParser.parse(graph);
                    List<GraphNode> nodes = target.graph().nodes().stream()
                            .map(node -> new GraphNode(node.id(), node.type().wireName(), node.settings()))
                            .toList();
                    return new PublishedGraph(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            new GraphView(target.graph().start(), nodes));
                });
        return SubflowCycles.problems(parsed.graph(), ownCode, catalog);
    }

    private static List<String> describe(List<Problem> problems) {
        List<String> described = new ArrayList<>();
        problems.forEach(problem -> described.add(problem.pointer() + " " + problem.code()));
        return described;
    }

    /** trigger -> subflow(code) -> end, or trigger -> end when the code is null. */
    private static Object chain(String subflowCode, String name) {
        if (subflowCode == null) {
            return Map.of(
                    "start", "start",
                    "nodes", List.of(
                            Map.of("id", "start", "type", "trigger", "next", "done"),
                            Map.of("id", "done", "type", "end")));
        }
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "sub"),
                        Map.of("id", "sub", "type", "subflow", "definition", subflowCode, "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}

package dev.ngb.workflow.definitions.application.definition.service;

import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.domain.definition.Graph;
import dev.ngb.workflow.definitions.domain.definition.NodeType;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Refuses a draft whose sub-workflows lead back to it. A sub-flow is a call into another published graph, so a cycle is
 * only visible across definitions; publishing is the moment the whole chain can be resolved.
 */
public final class SubflowCycles {

    private static final int MAX_DEPTH = 32;

    private SubflowCycles() {}

    /**
     * @param draft the graph about to be published
     * @param ownCode the code of the definition being published
     * @param catalog the newest published graph of a definition code, empty when it has none yet
     */
    public static List<Problem> problems(
            Graph draft, String ownCode, Function<String, Optional<PublishedGraph>> catalog) {
        List<Problem> problems = new ArrayList<>();
        for (int index = 0; index < draft.nodes().size(); index++) {
            var node = draft.nodes().get(index);
            if (node.type() != NodeType.SUBFLOW) {
                continue;
            }
            String code = node.text("definition");
            if (code == null) {
                continue;
            }
            Set<String> path = new HashSet<>();
            path.add(ownCode);
            if (cycles(code, path, catalog, 0)) {
                problems.add(new Problem(
                        "/nodes/" + index + "/definition",
                        ProblemCodes.SUBFLOW_CYCLE,
                        "the sub-workflow chain returns to " + code));
            }
        }
        return List.copyOf(problems);
    }

    private static boolean cycles(
            String code, Set<String> path, Function<String, Optional<PublishedGraph>> catalog, int depth) {
        if (path.contains(code) || depth > MAX_DEPTH) {
            return true;
        }
        Optional<PublishedGraph> graph = catalog.apply(code);
        if (graph.isEmpty()) {
            // An unpublished reference is a runtime failure with its own reason, not a publish-time cycle.
            return false;
        }
        Set<String> next = new HashSet<>(path);
        next.add(code);
        for (GraphNode node : graph.get().graph().nodes()) {
            if ("subflow".equals(node.type()) && node.text("definition") != null
                    && cycles(node.text("definition"), next, catalog, depth + 1)) {
                return true;
            }
        }
        return false;
    }
}

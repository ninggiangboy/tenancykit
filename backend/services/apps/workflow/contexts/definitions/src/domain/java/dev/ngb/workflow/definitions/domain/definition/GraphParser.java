package dev.ngb.workflow.definitions.domain.definition;

import dev.ngb.workflow.definitions.domain.expression.Json;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON graph document into {@link Node}s, or reports why it cannot.
 *
 * <p>It checks only what is about shape: the root is an object, {@code nodes} is an array, every entry is an object with
 * an id and a known type. Everything about how the nodes connect and what each type must carry belongs to
 * {@link StructureChecker}, so one report lists the shape problems and the structural ones together.
 */
public final class GraphParser {

    /** The tree, and every shape problem. A document whose root or node array is unreadable has a null graph. */
    public record Result(Graph graph, List<Problem> problems) {}

    private GraphParser() {}

    public static Result parse(Object document) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> root = Json.object(document);
        if (root == null) {
            problems.add(new Problem("/", ProblemCodes.GRAPH_INVALID, "a graph is an object with a start and nodes"));
            return new Result(null, List.copyOf(problems));
        }
        String start = Json.text(root.get("start"));
        if (start == null || start.isBlank()) {
            problems.add(new Problem("/start", ProblemCodes.GRAPH_INVALID, "a graph names the node it starts at"));
        }
        List<Object> documents = Json.array(root.get("nodes"));
        if (documents == null) {
            problems.add(new Problem("/nodes", ProblemCodes.GRAPH_INVALID, "a graph carries an array of nodes"));
            return new Result(null, List.copyOf(problems));
        }
        if (documents.isEmpty()) {
            problems.add(new Problem("/nodes", ProblemCodes.GRAPH_INVALID, "a graph carries at least one node"));
        }

        List<Node> nodes = new ArrayList<>(documents.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < documents.size(); index++) {
            Node node = node(documents.get(index), "/nodes/" + index, seen, problems);
            if (node != null) {
                nodes.add(node);
            }
        }
        return new Result(new Graph(start == null ? "" : start, nodes), List.copyOf(problems));
    }

    private static Node node(Object document, String pointer, Map<String, Integer> seen, List<Problem> problems) {
        Map<String, Object> members = Json.object(document);
        if (members == null) {
            problems.add(new Problem(pointer, ProblemCodes.GRAPH_INVALID, "a node is an object"));
            return null;
        }
        String id = Json.text(members.get("id"));
        if (id == null || id.isBlank()) {
            problems.add(new Problem(pointer + "/id", ProblemCodes.GRAPH_INVALID, "a node names its id"));
            return null;
        }
        NodeType type = NodeType.named(Json.text(members.get("type")));
        if (type == null) {
            problems.add(new Problem(
                    pointer + "/type", ProblemCodes.UNKNOWN_NODE_TYPE, "the graph language defines no node type by this name"));
            return null;
        }
        if (seen.putIfAbsent(id, members.size()) != null) {
            problems.add(new Problem(pointer + "/id", ProblemCodes.DUPLICATE_NODE, "another node carries this id"));
        }

        Map<String, Object> settings = new LinkedHashMap<>(members);
        settings.remove("id");
        settings.remove("type");
        return new Node(id, type, settings);
    }
}

package dev.ngb.workflow.definitions.domain.definition;

import dev.ngb.workflow.definitions.domain.expression.ExprChecker;
import dev.ngb.workflow.definitions.domain.expression.ExprParser;
import dev.ngb.workflow.definitions.domain.expression.Json;
import dev.ngb.workflow.definitions.domain.expression.Limits;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The real gate a graph passes before it can be published: what each node type must carry, where every edge leads, what
 * is reachable, which cycles are bounded, and every expression the graph holds.
 *
 * <p>Every failure names the RFC 6901 pointer of the offending member and a stable code, so a client can point at the
 * node it must fix and branch on the reason. The JSON schema carries the shape; this class carries the meaning, because
 * a schema cannot count nodes, follow edges or resolve a reference.
 */
public final class StructureChecker {

    /** Nodes in one graph. */
    public static final int MAX_NODES = 200;

    /** Edges in one graph, every kind counted. */
    public static final int MAX_EDGES = 400;

    private static final Pattern ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** Branches one fan-out may name, matching the runtime guard. */
    public static final int MAX_PARALLEL_BRANCHES = 50;

    private static final Set<String> JOIN_MODES = Set.of("all", "any", "n_of");

    private StructureChecker() {}

    public static List<Problem> problems(Graph graph) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Node> byId = graph.byId();
        Map<String, Integer> indexes = indexes(graph);

        if (graph.nodes().size() > MAX_NODES) {
            problems.add(new Problem(
                    "/nodes", ProblemCodes.LIMIT_EXCEEDED, "a graph holds at most " + MAX_NODES + " nodes"));
        }

        List<Edge> edges = new ArrayList<>();
        for (Node node : graph.nodes()) {
            String pointer = "/nodes/" + indexes.get(node.id());
            if (!ID.matcher(node.id()).matches()) {
                problems.add(new Problem(
                        pointer + "/id",
                        ProblemCodes.GRAPH_INVALID,
                        "a node id starts with a letter and holds letters, digits, '_' or '-'"));
            }
            type(node, pointer, byId, edges, problems);
        }

        if (edges.size() > MAX_EDGES) {
            problems.add(new Problem(
                    "/nodes", ProblemCodes.LIMIT_EXCEEDED, "a graph holds at most " + MAX_EDGES + " edges"));
        }

        trigger(graph, byId, indexes, problems);
        if (graph.nodes().stream().noneMatch(node -> node.type() == NodeType.END || node.type() == NodeType.FAIL)) {
            problems.add(new Problem(
                    "/nodes", ProblemCodes.GRAPH_INVALID, "a graph ends at an end or a fail node"));
        }
        targets(edges, byId, problems);
        joins(graph, indexes, problems);
        reachable(graph, byId, edges, indexes, problems);
        cycles(graph, byId, edges, problems);
        nestedIterations(graph, byId, edges, indexes, problems);
        expressions(graph, byId, indexes, edges, problems);
        return List.copyOf(problems);
    }

    // ------------------------------------------------------------------ node types

    private static void type(
            Node node,
            String pointer,
            Map<String, Node> byId,
            List<Edge> edges,
            List<Problem> problems) {
        switch (node.type()) {
            case TRIGGER -> {
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
            }
            case SET -> {
                if (node.object("values").isEmpty()) {
                    problems.add(new Problem(
                            pointer + "/values", ProblemCodes.GRAPH_INVALID, "a set node names at least one variable"));
                }
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
            }
            case HTTP -> {
                required(node, pointer, "app", problems);
                String method = required(node, pointer, "method", problems);
                if (method != null && !METHODS.contains(method)) {
                    problems.add(new Problem(
                            pointer + "/method", ProblemCodes.GRAPH_INVALID, "an http node names a method GET, POST, PUT, PATCH or DELETE"));
                }
                required(node, pointer, "path", problems);
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
                edge(node, pointer, "on_error", edges);
                edge(node, pointer, "compensate", edges);
            }
            case BRANCH -> {
                List<Object> cases = node.list("cases");
                if (cases.isEmpty()) {
                    problems.add(new Problem(
                            pointer + "/cases", ProblemCodes.GRAPH_INVALID, "a branch node names at least one case"));
                }
                for (int index = 0; index < cases.size(); index++) {
                    Map<String, Object> test = Json.object(cases.get(index));
                    String casePointer = pointer + "/cases/" + index;
                    if (test == null) {
                        problems.add(new Problem(casePointer, ProblemCodes.GRAPH_INVALID, "a case is an object"));
                        continue;
                    }
                    if (test.get("when") == null) {
                        problems.add(new Problem(
                                casePointer + "/when", ProblemCodes.GRAPH_INVALID, "a case names its condition"));
                    }
                    String next = Json.text(test.get("next"));
                    if (next == null || next.isBlank()) {
                        problems.add(new Problem(
                                casePointer + "/next", ProblemCodes.GRAPH_INVALID, "a case names what follows it"));
                    } else {
                        edges.add(new Edge(node.id(), casePointer + "/next", next, false));
                    }
                }
                edge(node, pointer, "else", edges);
            }
            case DELAY -> {
                required(node, pointer, "duration", problems);
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
            }
            case WAIT -> {
                required(node, pointer, "event", problems);
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
                edge(node, pointer, "on_timeout", edges);
            }
            case TASK -> {
                required(node, pointer, "title", problems);
                if (node.object("assignee").isEmpty()) {
                    problems.add(new Problem(
                            pointer + "/assignee", ProblemCodes.GRAPH_INVALID, "a task node names who takes it"));
                }
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
                edge(node, pointer, "on_error", edges);
                edge(node, pointer, "on_expire", edges);
                edge(node, pointer, "compensate", edges);
            }
            case PARALLEL -> {
                List<String> branches = node.textList("branches");
                if (branches.size() < 2) {
                    problems.add(new Problem(
                            pointer + "/branches", ProblemCodes.GRAPH_INVALID, "a parallel node names at least two branches"));
                }
                if (branches.size() > MAX_PARALLEL_BRANCHES) {
                    problems.add(new Problem(
                            pointer + "/branches",
                            ProblemCodes.LIMIT_EXCEEDED,
                            "a parallel node names at most " + MAX_PARALLEL_BRANCHES + " branches"));
                }
                if (new HashSet<>(branches).size() != branches.size()) {
                    problems.add(new Problem(
                            pointer + "/branches", ProblemCodes.GRAPH_INVALID, "a branch is collected under its node id, so the ids are distinct"));
                }
                for (int index = 0; index < branches.size(); index++) {
                    edges.add(new Edge(node.id(), pointer + "/branches/" + index, branches.get(index), false));
                }
                String join = required(node, pointer, "join", problems);
                if (join != null) {
                    Node target = byId.get(join);
                    if (target == null) {
                        problems.add(new Problem(
                                pointer + "/join", ProblemCodes.MISSING_TARGET, "no node of this graph carries this id"));
                    } else if (target.type() != NodeType.JOIN) {
                        problems.add(new Problem(
                                pointer + "/join", ProblemCodes.PARALLEL_WITHOUT_JOIN, "a parallel node joins at a join node"));
                    }
                    edges.add(new Edge(node.id(), pointer + "/join", join, false));
                }
            }
            case JOIN -> {
                required(node, pointer, "next", problems);
                String mode = node.text("mode");
                if (mode != null && !JOIN_MODES.contains(mode)) {
                    problems.add(new Problem(
                            pointer + "/mode", ProblemCodes.GRAPH_INVALID, "a join waits for all, any or n_of"));
                }
                if ("n_of".equals(mode)) {
                    Object count = node.document("count");
                    if (!(count instanceof Number number)
                            || number.intValue() < 1
                            || number.intValue() > MAX_PARALLEL_BRANCHES) {
                        problems.add(new Problem(
                                pointer + "/count",
                                ProblemCodes.GRAPH_INVALID,
                                "an n_of join names a count between 1 and " + MAX_PARALLEL_BRANCHES));
                    }
                }
                edge(node, pointer, "next", edges);
                edge(node, pointer, "on_error", edges);
            }
            case FOREACH -> {
                if (node.document("items") == null) {
                    problems.add(new Problem(
                            pointer + "/items", ProblemCodes.GRAPH_INVALID, "a for-each node names what it iterates"));
                }
                required(node, pointer, "each", problems);
                required(node, pointer, "next", problems);
                edge(node, pointer, "each", edges);
                edge(node, pointer, "next", edges);
            }
            case SUBFLOW -> {
                required(node, pointer, "definition", problems);
                required(node, pointer, "next", problems);
                edge(node, pointer, "next", edges);
                edge(node, pointer, "on_error", edges);
                edge(node, pointer, "compensate", edges);
            }
            case END -> {
                if (node.next() != null) {
                    problems.add(new Problem(pointer + "/next", ProblemCodes.GRAPH_INVALID, "an end node has no next"));
                }
            }
            case FAIL -> edge(node, pointer, "on_error", edges);
        }
    }

    /** A member a node type cannot do without. */
    private static String required(Node node, String pointer, String name, List<Problem> problems) {
        String value = node.text(name);
        if (value == null || value.isBlank()) {
            problems.add(new Problem(
                    pointer + "/" + name, ProblemCodes.GRAPH_INVALID, "a " + node.type().wireName() + " node needs " + name));
            return null;
        }
        return value;
    }

    /** One text edge, when the member is present. */
    private static void edge(Node node, String pointer, String name, List<Edge> edges) {
        String target = node.text(name);
        if (target != null && !target.isBlank()) {
            edges.add(new Edge(node.id(), pointer + "/" + name, target, "compensate".equals(name)));
        }
    }

    // ------------------------------------------------------------------ whole graph

    private static void trigger(Graph graph, Map<String, Node> byId, Map<String, Integer> indexes, List<Problem> problems) {
        List<Node> triggers = graph.nodes().stream().filter(node -> node.type() == NodeType.TRIGGER).toList();
        if (triggers.size() != 1) {
            problems.add(new Problem(
                    "/start", ProblemCodes.TRIGGER_INVALID, "a graph has exactly one trigger node"));
        }
        Node start = byId.get(graph.start());
        if (start == null) {
            problems.add(new Problem(
                    "/start", ProblemCodes.MISSING_TARGET, "the start names no node of this graph"));
        } else if (start.type() != NodeType.TRIGGER) {
            problems.add(new Problem("/start", ProblemCodes.TRIGGER_INVALID, "the start is the trigger node"));
        }
        for (Node node : triggers) {
            if (!node.id().equals(graph.start())) {
                problems.add(new Problem(
                        "/nodes/" + indexes.get(node.id()),
                        ProblemCodes.TRIGGER_INVALID,
                        "the only trigger node is the start"));
            }
        }
    }

    private static void targets(List<Edge> edges, Map<String, Node> byId, List<Problem> problems) {
        for (Edge edge : edges) {
            Node target = byId.get(edge.target());
            if (target == null) {
                problems.add(new Problem(
                        edge.pointer(), ProblemCodes.MISSING_TARGET, "no node of this graph carries this id"));
            } else if (edge.compensation() && target.type() != NodeType.HTTP) {
                problems.add(new Problem(
                        edge.pointer(), ProblemCodes.COMPENSATE_INVALID, "a compensation is an http node"));
            }
        }
    }

    private static void joins(Graph graph, Map<String, Integer> indexes, List<Problem> problems) {
        Map<String, Integer> branchCounts = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            if (node.type() == NodeType.PARALLEL) {
                branchCounts.put(node.text("join"), node.textList("branches").size());
            }
        }
        for (Node node : graph.nodes()) {
            if (node.type() != NodeType.JOIN) {
                continue;
            }
            if (!branchCounts.containsKey(node.id())) {
                problems.add(new Problem(
                        "/nodes/" + indexes.get(node.id()),
                        ProblemCodes.JOIN_WITHOUT_PARALLEL,
                        "a join node is the join of a parallel node"));
                continue;
            }
            if ("n_of".equals(node.text("mode"))
                    && node.document("count") instanceof Number number
                    && number.intValue() > branchCounts.get(node.id())) {
                problems.add(new Problem(
                        "/nodes/" + indexes.get(node.id()) + "/count",
                        ProblemCodes.GRAPH_INVALID,
                        "an n_of join waits for at most its parallel's branches"));
            }
        }
    }

    private static void reachable(
            Graph graph, Map<String, Node> byId, List<Edge> edges, Map<String, Integer> indexes, List<Problem> problems) {
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (byId.containsKey(graph.start())) {
            queue.add(graph.start());
            reached.add(graph.start());
        }
        while (!queue.isEmpty()) {
            String id = queue.remove();
            for (Edge edge : edges) {
                if (edge.source().equals(id) && byId.containsKey(edge.target()) && reached.add(edge.target())) {
                    queue.add(edge.target());
                }
            }
        }
        for (Node node : graph.nodes()) {
            if (!reached.contains(node.id())) {
                problems.add(new Problem(
                        "/nodes/" + indexes.get(node.id()),
                        ProblemCodes.UNREACHABLE_NODE,
                        "no path from the start reaches this node"));
            }
        }
    }

    /** A cycle is allowed only when it is bounded by a for-each on its path. */
    private static void cycles(Graph graph, Map<String, Node> byId, List<Edge> edges, List<Problem> problems) {
        Set<String> visited = new HashSet<>();
        List<String> stack = new ArrayList<>();
        Set<String> onStack = new HashSet<>();
        for (Node node : graph.nodes()) {
            if (!visited.contains(node.id())) {
                visit(node.id(), byId, edges, visited, stack, onStack, problems);
            }
        }
    }

    private static void visit(
            String id,
            Map<String, Node> byId,
            List<Edge> edges,
            Set<String> visited,
            List<String> stack,
            Set<String> onStack,
            List<Problem> problems) {
        visited.add(id);
        onStack.add(id);
        stack.add(id);
        for (Edge edge : edges) {
            if (edge.compensation() || !edge.source().equals(id) || !byId.containsKey(edge.target())) {
                continue;
            }
            if (onStack.contains(edge.target())) {
                int from = stack.indexOf(edge.target());
                boolean bounded = stack.subList(from, stack.size()).stream()
                        .map(byId::get)
                        .anyMatch(node -> node != null && node.type() == NodeType.FOREACH);
                if (!bounded) {
                    problems.add(new Problem(
                            edge.pointer(),
                            ProblemCodes.UNBOUNDED_CYCLE,
                            "a cycle repeats only through a for-each"));
                }
            } else if (!visited.contains(edge.target())) {
                visit(edge.target(), byId, edges, visited, stack, onStack, problems);
            }
        }
        stack.remove(stack.size() - 1);
        onStack.remove(id);
    }

    /**
     * A for-each body holds no other for-each. The runtime keeps one loop per token, so a nested loop would need a
     * stack; v1 refuses it at publish rather than guess what the inner loop should do.
     */
    private static void nestedIterations(
            Graph graph, Map<String, Node> byId, List<Edge> edges, Map<String, Integer> indexes, List<Problem> problems) {
        Set<String> inside = insideIterations(graph, byId, edges);
        for (Node node : graph.nodes()) {
            if (node.type() != NodeType.FOREACH || !inside.contains(node.id())) {
                continue;
            }
            Node owner = graph.nodes().stream()
                    .filter(candidate -> candidate.type() == NodeType.FOREACH
                            && !candidate.id().equals(node.id())
                            && reaches(candidate, node.id(), byId, edges))
                    .findFirst()
                    .orElse(null);
            if (owner != null) {
                problems.add(new Problem(
                        "/nodes/" + indexes.get(node.id()) + "/type",
                        ProblemCodes.GRAPH_INVALID,
                        "a for-each body holds no other for-each; split it into a sub-workflow"));
            }
        }
    }

    /** Whether the body of one for-each reaches a node. */
    private static boolean reaches(Node loop, String target, Map<String, Node> byId, List<Edge> edges) {
        String each = loop.text("each");
        if (each == null || !byId.containsKey(each)) {
            return false;
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(each);
        seen.add(each);
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (id.equals(target)) {
                return true;
            }
            for (Edge edge : edges) {
                if (edge.compensation() || !edge.source().equals(id) || !byId.containsKey(edge.target())
                        || edge.target().equals(loop.id()) || !seen.add(edge.target())) {
                    continue;
                }
                queue.add(edge.target());
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ expressions

    private static void expressions(
            Graph graph,
            Map<String, Node> byId,
            Map<String, Integer> indexes,
            List<Edge> edges,
            List<Problem> problems) {
        Set<String> variables = new LinkedHashSet<>();
        for (Node node : graph.nodes()) {
            if (node.type() == NodeType.SET) {
                variables.addAll(node.object("values").keySet());
            }
        }
        Set<String> insideIteration = insideIterations(graph, byId, edges);
        int total = 0;
        for (Reference reference : references(graph, indexes)) {
            ExprParser.Result parsed = ExprParser.parse(reference.document(), reference.pointer());
            problems.addAll(parsed.problems());
            if (parsed.expression() == null) {
                continue;
            }
            total += parsed.expression().nodes();
            problems.addAll(ExprChecker.problems(
                    parsed.expression(),
                    reference.pointer(),
                    new ExprChecker.Names(byId.keySet(), variables, insideIteration.contains(reference.nodeId()))));
        }
        if (total > Limits.VERSION_NODES) {
            problems.add(new Problem(
                    "/nodes",
                    ProblemCodes.LIMIT_EXCEEDED,
                    "a version holds at most " + Limits.VERSION_NODES + " expression nodes"));
        }
    }

    private record Reference(String nodeId, String pointer, Object document) {}

    private static List<Reference> references(Graph graph, Map<String, Integer> indexes) {
        List<Reference> references = new ArrayList<>();
        for (Node node : graph.nodes()) {
            String base = "/nodes/" + indexes.get(node.id());
            switch (node.type()) {
                case SET -> node.object("values")
                        .forEach((name, document) -> add(references, node, base + "/values/" + name, document));
                case BRANCH -> {
                    List<Object> cases = node.list("cases");
                    for (int index = 0; index < cases.size(); index++) {
                        Map<String, Object> test = Json.object(cases.get(index));
                        if (test != null) {
                            add(references, node, base + "/cases/" + index + "/when", test.get("when"));
                        }
                    }
                }
                case WAIT -> add(references, node, base + "/match", node.document("match"));
                case FOREACH -> add(references, node, base + "/items", node.document("items"));
                case SUBFLOW -> add(references, node, base + "/input", node.document("input"));
                case FAIL -> add(references, node, base + "/reason", node.document("reason"));
                default -> {}
            }
        }
        return references;
    }

    private static void add(List<Reference> references, Node node, String pointer, Object document) {
        if (document != null) {
            references.add(new Reference(node.id(), pointer, document));
        }
    }

    /** The nodes an expression may read the current item from: whatever a for-each body reaches. */
    private static Set<String> insideIterations(Graph graph, Map<String, Node> byId, List<Edge> edges) {
        Set<String> inside = new HashSet<>();
        for (Node node : graph.nodes()) {
            if (node.type() != NodeType.FOREACH) {
                continue;
            }
            String each = node.text("each");
            if (each == null || !byId.containsKey(each)) {
                continue;
            }
            Deque<String> queue = new ArrayDeque<>();
            queue.add(each);
            inside.add(each);
            while (!queue.isEmpty()) {
                String id = queue.remove();
                for (Edge edge : edges) {
                    if (edge.compensation() || !edge.source().equals(id) || !byId.containsKey(edge.target())
                            || edge.target().equals(node.id()) || !inside.add(edge.target())) {
                        continue;
                    }
                    queue.add(edge.target());
                }
            }
        }
        return inside;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Integer> indexes(Graph graph) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            indexes.putIfAbsent(graph.nodes().get(index).id(), index);
        }
        return indexes;
    }

    /** One edge as a document states it: the node it leaves, the pointer that reports it and the node it enters. */
    private record Edge(String source, String pointer, String target, boolean compensation) {}
}

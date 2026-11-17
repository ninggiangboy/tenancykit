package dev.ngb.workflow.definitions.application.definition.service;

import dev.ngb.workflow.definitions.domain.definition.Graph;
import dev.ngb.workflow.definitions.domain.definition.Node;
import dev.ngb.workflow.definitions.domain.expression.Evaluator;
import dev.ngb.workflow.definitions.domain.expression.ExprParser;
import dev.ngb.workflow.definitions.domain.expression.Json;
import dev.ngb.workflow.definitions.domain.expression.MapEnvironment;
import dev.ngb.workflow.definitions.domain.expression.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a draft graph over a sample payload without any side effect: it never calls out, never waits and never writes.
 *
 * <p>It follows the nodes whose behaviour is pure (the trigger, a {@code set}, a {@code branch}) and stops at the first
 * node that would act on the world, reporting which one. That is what an author needs before publishing: the branch
 * decisions the sample payload takes, the variables they set, and where the path leaves the pure part of the graph.
 */
public class GraphSimulation {

    /** One branch node and the case the sample payload took; {@code caseIndex} is null for the else edge. */
    public record Decision(String node, Integer caseIndex, String next) {}

    /**
     * @param path the node ids visited, in order
     * @param decisions every branch the walk took
     * @param variables every variable set on the way
     * @param stoppedAt the node type the walk stopped at, or {@code end}/{@code fail}/{@code no_next}
     */
    public record Result(List<String> path, List<Decision> decisions, Map<String, Object> variables, String stoppedAt) {}

    private static final int MAX_STEPS = 200;

    public Result run(Graph graph, Map<String, Object> input, Map<String, Object> steps) {
        Map<String, Node> byId = graph.byId();
        Map<String, Object> variables = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        String current = graph.start();

        for (int walked = 0; current != null && walked < MAX_STEPS; walked++) {
            Node node = byId.get(current);
            if (node == null) {
                return new Result(List.copyOf(path), List.copyOf(decisions), Map.copyOf(variables), "missing_node");
            }
            path.add(node.id());
            switch (node.type()) {
                case TRIGGER -> current = node.next();
                case SET -> {
                    node.object("values").forEach((name, document) -> {
                        Object value = evaluate(document, input, steps, variables);
                        variables.put(name, value);
                    });
                    current = node.next();
                }
                case BRANCH -> {
                    Choice choice = choose(node, input, steps, variables);
                    decisions.add(new Decision(node.id(), choice.caseIndex(), choice.next()));
                    current = choice.next();
                }
                case END, FAIL -> {
                    return new Result(List.copyOf(path), List.copyOf(decisions), Map.copyOf(variables),
                            node.type().wireName());
                }
                default -> {
                    return new Result(List.copyOf(path), List.copyOf(decisions), Map.copyOf(variables),
                            "node_not_simulated:" + node.type().wireName());
                }
            }
        }
        return new Result(
                List.copyOf(path),
                List.copyOf(decisions),
                Map.copyOf(variables),
                current == null ? "no_next" : "step_limit");
    }

    private record Choice(Integer caseIndex, String next) {}

    private Choice choose(Node node, Map<String, Object> input, Map<String, Object> steps, Map<String, Object> variables) {
        List<Object> cases = node.list("cases");
        for (int index = 0; index < cases.size(); index++) {
            Map<String, Object> test = Json.object(cases.get(index));
            if (test == null) {
                continue;
            }
            Object value = evaluate(test.get("when"), input, steps, variables);
            if (value instanceof Boolean truth && truth) {
                return new Choice(index, Json.text(test.get("next")));
            }
        }
        return new Choice(null, node.text("else"));
    }

    /** One expression over the sample payload and the variables set so far. An unreadable one is null, not a failure. */
    private Object evaluate(Object document, Map<String, Object> input, Map<String, Object> steps, Map<String, Object> variables) {
        ExprParser.Result parsed = ExprParser.parse(document, "/simulate");
        if (parsed.expression() == null) {
            return null;
        }
        Value value = Evaluator.evaluate(parsed.expression(), new MapEnvironment(input, steps, variables, null, null));
        return Value.json(value);
    }
}

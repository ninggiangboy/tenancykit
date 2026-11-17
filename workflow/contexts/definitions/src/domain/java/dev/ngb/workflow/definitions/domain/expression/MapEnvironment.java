package dev.ngb.workflow.definitions.domain.expression;

import java.math.BigDecimal;
import java.util.Map;

/**
 * An environment over plain JSON values, which is what a simulation passes and a test builds.
 *
 * <p>An absent {@code steps} or {@code vars} member is an empty map; an absent {@code item} or {@code index} means the
 * expression is not running inside a for-each.
 */
public record MapEnvironment(
        Object input, Map<String, Object> steps, Map<String, Object> vars, Object item, Integer iterationIndex)
        implements Environment {

    public MapEnvironment {
        steps = frozen(steps);
        vars = frozen(vars);
    }

    /** A variable may hold null, so the copy is unmodifiable rather than {@code Map.copyOf}. */
    private static Map<String, Object> frozen(Map<String, Object> values) {
        return values == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    /** An environment with no values, which is what an empty draft simulates against. */
    public static MapEnvironment empty() {
        return new MapEnvironment(null, Map.of(), Map.of(), null, null);
    }

    @Override
    public Value input(String path) {
        return Json.at(input, path);
    }

    @Override
    public Value step(String nodeId, String path) {
        return Json.at(steps.get(nodeId), path);
    }

    @Override
    public Value variable(String name) {
        return Json.at(vars.get(name), "");
    }

    @Override
    public Value item(String path) {
        return Json.at(item, path);
    }

    @Override
    public Value index() {
        return iterationIndex == null ? Value.NIL : new Value.Num(Value.number(BigDecimal.valueOf(iterationIndex)));
    }
}

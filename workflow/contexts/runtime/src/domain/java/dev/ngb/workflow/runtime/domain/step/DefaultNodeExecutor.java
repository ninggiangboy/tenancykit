package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The core's node behaviour before any outward call exists: the trigger, a {@code set}, the end and a deliberate fail.
 *
 * <p>Every other node type reports itself as unsupported for now, so an instance that reaches one stops with a reason
 * instead of pretending to have done something the engine cannot do yet. The phases that add an http call, a delay, a
 * wait, a task, a parallel, a for-each or a subflow each extend this class.
 */
public class DefaultNodeExecutor implements NodeExecutor {

    private final ExpressionEvaluator expressions;

    public DefaultNodeExecutor(ExpressionEvaluator expressions) {
        this.expressions = expressions;
    }

    @Override
    public StepOutcome execute(ExecutionContext context) {
        return switch (context.node().type()) {
            case "trigger" -> StepOutcome.succeeded(null, context.node().next(), context.variables());
            case "set" -> set(context);
            case "end" -> StepOutcome.succeeded("ended", null, context.variables());
            case "fail" -> fail(context);
            default -> StepOutcome.failed(
                    "node_not_supported:" + context.node().type(), null, context.variables());
        };
    }

    private StepOutcome set(ExecutionContext context) {
        Map<String, Object> variables = new LinkedHashMap<>(context.variables());
        Map<String, Object> values = object(context.node().document("values"));
        values.forEach((name, expression) -> variables.put(
                name,
                expressions.evaluate(
                        expression,
                        input(context.instance().input()),
                        Map.of(),
                        variables,
                        null,
                        null)));
        return StepOutcome.succeeded(variables, context.node().next(), variables);
    }

    private StepOutcome fail(ExecutionContext context) {
        Object reason = context.node().document("reason");
        Object evaluated = reason == null
                ? null
                : expressions.evaluate(reason, input(context.instance().input()), Map.of(), context.variables(), null, null);
        return StepOutcome.failed(
                evaluated instanceof String text ? text : "the workflow failed at this node",
                evaluated,
                context.variables());
    }

    private static Map<String, Object> input(Object value) {
        return value instanceof Map<?, ?> ? object(value) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> members ? (Map<String, Object>) members : Map.of();
    }
}

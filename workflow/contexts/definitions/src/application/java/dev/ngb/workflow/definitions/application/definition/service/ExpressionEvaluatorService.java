package dev.ngb.workflow.definitions.application.definition.service;

import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.domain.expression.Evaluator;
import dev.ngb.workflow.definitions.domain.expression.ExprParser;
import dev.ngb.workflow.definitions.domain.expression.Json;
import dev.ngb.workflow.definitions.domain.expression.MapEnvironment;
import dev.ngb.workflow.definitions.domain.expression.Value;
import java.util.Map;

/** Serves the runtime the expression language. The evaluator stays pure: no clock, no network and no host access. */
public class ExpressionEvaluatorService implements ExpressionEvaluator {

    @Override
    public Object evaluate(
            Object expression,
            Map<String, Object> input,
            Map<String, Object> steps,
            Map<String, Object> variables,
            Object item,
            Integer index) {
        ExprParser.Result parsed = ExprParser.parse(expression, "/expression");
        if (parsed.expression() == null) {
            return null;
        }
        Value value = Evaluator.evaluate(
                parsed.expression(), new MapEnvironment(input, steps, variables, item, index));
        return Value.json(value);
    }

    @Override
    public Object document(
            Object expression,
            Map<String, Object> input,
            Map<String, Object> steps,
            Map<String, Object> variables,
            Object item,
            Integer index) {
        if (expression instanceof Map<?, ?> members) {
            Object raw = reference(members, input, steps, variables, item, index);
            if (raw != Reference.NOT_A_REFERENCE) {
                return Json.missing(raw) ? null : raw;
            }
        }
        return evaluate(expression, input, steps, variables, item, index);
    }

    /** The four reference forms, resolved against the document itself; anything else is an expression. */
    private static Object reference(
            Map<?, ?> members,
            Map<String, Object> input,
            Map<String, Object> steps,
            Map<String, Object> variables,
            Object item,
            Integer index) {
        if (members.size() == 1 && members.containsKey("input")) {
            return Json.raw(input, String.valueOf(members.get("input")));
        }
        if (members.containsKey("item") && members.size() == 1) {
            return Json.raw(item, String.valueOf(members.get("item")));
        }
        if (members.size() == 1 && Boolean.TRUE.equals(members.get("index"))) {
            return index;
        }
        if (members.size() == 1 && members.get("var") instanceof String name) {
            return variables.containsKey(name) ? variables.get(name) : Json.Missing.INSTANCE;
        }
        if (members.get("step") instanceof String nodeId) {
            String path = members.get("path") instanceof String text ? text : "";
            return Json.raw(steps.get(nodeId), path);
        }
        return Reference.NOT_A_REFERENCE;
    }

    private enum Reference {
        NOT_A_REFERENCE
    }
}

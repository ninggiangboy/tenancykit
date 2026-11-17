package dev.ngb.workflow.definitions.application.definition.service;

import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.domain.expression.Evaluator;
import dev.ngb.workflow.definitions.domain.expression.ExprParser;
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
}

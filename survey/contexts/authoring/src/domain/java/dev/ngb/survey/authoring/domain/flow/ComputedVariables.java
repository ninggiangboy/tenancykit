package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.expression.Evaluator;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates a version's computed variables in declared order, after the answers.
 *
 * <p>Declared order is what makes the graph acyclic: a variable sees only the ones already computed, so a reference
 * to a later one reads as null and validation reports the mistake. Evaluation never fails on data.
 */
public final class ComputedVariables {

    private ComputedVariables() {}

    public static Map<String, Value> evaluate(FlowPlan plan, FlowEnvironment environment) {
        Map<String, Value> values = new LinkedHashMap<>();
        FlowEnvironment withVariables = environment.with(values);
        for (FlowPlan.Computed variable : plan.computed()) {
            if (variable.name() == null || variable.expr() == null) {
                continue;
            }
            Value value = Evaluator.evaluate(variable.expr(), withVariables);
            values.put(variable.name(), value);
            withVariables = environment.with(values);
        }
        return values;
    }
}

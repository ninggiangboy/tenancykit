package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.expression.Evaluator;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one flow run can read: a version's answers and, once they have been computed, its variables.
 *
 * <p>The variables start empty, because a path is resolved from the answers alone — a jump or a {@code skip_if} may
 * not depend on a value that is computed after the answers. A variable reference during that phase reads as null,
 * which is the same answer an unresolvable reference gives.
 */
public final class FlowEnvironment implements Evaluator.Environment {

    private final Answers answers;
    private final Map<String, Value> variables;

    private FlowEnvironment(Answers answers, Map<String, Value> variables) {
        this.answers = answers;
        this.variables = variables;
    }

    /** The answers alone, which is what the path, visibility and the jumps run over. */
    public static FlowEnvironment of(Answers answers) {
        return new FlowEnvironment(answers, Map.of());
    }

    /** The same answers with the computed variables available. */
    public FlowEnvironment with(Map<String, Value> computed) {
        return new FlowEnvironment(answers, new LinkedHashMap<>(computed));
    }

    public Answers answers() {
        return answers;
    }

    public Map<String, Value> variables() {
        return variables;
    }

    @Override
    public Value answer(String questionId, String rowId) {
        return answers.value(questionId, rowId);
    }

    @Override
    public Value variable(String name) {
        return variables.getOrDefault(name, Value.NIL);
    }
}

package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.domain.expression.Evaluator;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run of a version's logic over a set of answers: the visited pages, every question's visibility, the computed
 * variables, the score and any structural error.
 *
 * <p>Order matters. The path and the visibility are resolved from the answers alone; the computed variables follow,
 * in declared order; the score is last, because a rule may read a variable. Evaluation never throws, so a simulate
 * call on a half-built draft still answers.
 */
public final class FlowRunner {

    private FlowRunner() {}

    public static FlowResult run(FlowPlan plan, Answers answers) {
        if (!plan.errors().isEmpty()) {
            return new FlowResult(List.of(), Map.of(), Map.of(), null, plan.errors());
        }
        FlowEnvironment answersOnly = FlowEnvironment.of(answers);
        List<String> path = PathResolver.resolve(plan, answersOnly);
        Map<String, Boolean> visible = Visibility.of(plan, path, answersOnly);

        Map<String, Value> variables = ComputedVariables.evaluate(plan, answersOnly);
        FlowEnvironment withVariables = answersOnly.with(variables);

        BigDecimal score = Scorer.score(plan, answers, visible, withVariables);
        return new FlowResult(path, visible, jsonOf(variables), score, List.of());
    }

    /** The variables as the API carries them, which is the JSON a number, a flag, text or a list becomes. */
    private static Map<String, Object> jsonOf(Map<String, Value> variables) {
        Map<String, Object> json = new LinkedHashMap<>();
        variables.forEach((name, value) -> json.put(name, Value.json(value)));
        return json;
    }

    /**
     * Renders every translation key of one locale: a piped sentence substitutes its tokens, and plain text renders as
     * itself, so a caller compares one shape whether the version pipes anything or not.
     *
     * @return the rendered sentences, keyed by translation key
     */
    public static Map<String, String> rendered(FlowPlan plan, Answers answers, String locale) {
        Map<String, Object> translations = plan.translations(locale);
        Map<String, Value> variables = ComputedVariables.evaluate(plan, FlowEnvironment.of(answers));
        Evaluator.Environment environment = FlowEnvironment.of(answers).with(variables);

        Map<String, String> rendered = new LinkedHashMap<>();
        translations.forEach((key, stored) -> rendered.put(key, PipedText.render(stored, environment)));
        return rendered;
    }

    /** The structural errors of a version document, without running it. */
    public static List<ValidationError> problems(Map<String, Object> document) {
        return FlowPlan.compile(document).errors();
    }
}

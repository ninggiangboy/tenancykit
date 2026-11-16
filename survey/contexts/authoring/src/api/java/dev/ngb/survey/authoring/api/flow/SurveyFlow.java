package dev.ngb.survey.authoring.api.flow;

import java.util.Map;

/**
 * The version logic as the other contexts use it: {@code collection} runs it over a submitted response and
 * {@code insight} runs it over an analytics filter's answers.
 *
 * <p>One evaluator for every caller is what keeps a branch or a score from disagreeing between the builder's preview,
 * the respondent's submit and the analytics, and a future fill UI passes the same vectors this runs.
 */
public interface SurveyFlow {

    /**
     * Runs a version document over a set of answers.
     *
     * @param document a version document, draft or published
     * @param answers the submitted answers, keyed by stable question id
     * @return the flow, or the structural errors that stopped it
     */
    FlowResult run(Map<String, Object> document, Map<String, Object> answers);

    /** Every structural reason a document could not be evaluated, by pointer; empty for a usable version. */
    java.util.List<dev.ngb.survey.authoring.api.document.ValidationError> problems(Map<String, Object> document);

    /**
     * Renders every translation key of one locale whose value pipes a value into the sentence.
     *
     * @return the rendered sentences keyed by translation key; empty when the locale pipes nothing
     */
    Map<String, String> rendered(Map<String, Object> document, Map<String, Object> answers, String locale);
}

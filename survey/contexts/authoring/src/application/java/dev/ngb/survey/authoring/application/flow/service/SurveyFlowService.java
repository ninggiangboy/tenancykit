package dev.ngb.survey.authoring.application.flow.service;

import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.api.flow.SurveyFlow;
import dev.ngb.survey.authoring.domain.flow.Answers;
import dev.ngb.survey.authoring.domain.flow.FlowPlan;
import dev.ngb.survey.authoring.domain.flow.FlowRunner;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of the version logic other contexts call: it compiles the document and runs it.
 *
 * <p>It is a service rather than a use case because it opens no transaction and changes nothing: a preview, a
 * simulate and a submit all ask the same question about the same document, and the caller already owns its unit of
 * work.
 */
public class SurveyFlowService implements SurveyFlow {

    @Override
    public FlowResult run(Map<String, Object> document, Map<String, Object> answers) {
        FlowPlan plan = FlowPlan.compile(document);
        return FlowRunner.run(plan, Answers.of(answers));
    }

    @Override
    public List<ValidationError> problems(Map<String, Object> document) {
        return FlowRunner.problems(document);
    }

    @Override
    public Map<String, String> rendered(Map<String, Object> document, Map<String, Object> answers, String locale) {
        return FlowRunner.rendered(FlowPlan.compile(document), Answers.of(answers), locale);
    }
}

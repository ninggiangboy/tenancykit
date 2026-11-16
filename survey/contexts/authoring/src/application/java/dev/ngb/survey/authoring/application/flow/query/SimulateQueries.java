package dev.ngb.survey.authoring.application.flow.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.api.flow.SurveyFlow;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a draft or a published version against a set of answers without changing anything.
 *
 * <p>The read happens inside a tenant-scoped transaction, because row-level security only narrows a query that runs
 * inside one; the evaluation itself is pure and needs no transaction. A survey of another tenant raises the same
 * not-found a missing one does, so the endpoint cannot be used to probe for ids.
 */
public class SimulateQueries {

    private final UnitOfWork unitOfWork;
    private final FlowDocumentReadRepository documents;
    private final SurveyFlow flow;

    public SimulateQueries(UnitOfWork unitOfWork, FlowDocumentReadRepository documents, SurveyFlow flow) {
        this.unitOfWork = unitOfWork;
        this.documents = documents;
        this.flow = flow;
    }

    /** Simulates the current draft, so a builder can try a branch before publishing. */
    public FlowResult draft(UUID surveyId, Map<String, Object> answers) {
        Map<String, Object> document = unitOfWork.transactional(() -> documents.draftDocument(surveyId)
                .orElseThrow(() -> new NotFoundException("No survey exists here.")));
        return flow.run(document, answers);
    }

    /** Simulates one published version, which is the logic a respondent of that version actually runs. */
    public FlowResult version(UUID surveyId, int versionNo, Map<String, Object> answers) {
        Map<String, Object> document = unitOfWork.transactional(() -> documents.versionDocument(surveyId, versionNo)
                .orElseThrow(() -> new NotFoundException("No version exists here.")));
        return flow.run(document, answers);
    }
}

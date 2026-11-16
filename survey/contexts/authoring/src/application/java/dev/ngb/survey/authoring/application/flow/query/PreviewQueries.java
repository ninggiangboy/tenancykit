package dev.ngb.survey.authoring.application.flow.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.application.flow.service.PreviewService;
import java.util.UUID;

/** Renders the current draft for one locale, read inside a tenant-scoped transaction. */
public class PreviewQueries {

    private final UnitOfWork unitOfWork;
    private final FlowDocumentReadRepository documents;
    private final PreviewService preview;

    public PreviewQueries(UnitOfWork unitOfWork, FlowDocumentReadRepository documents, PreviewService preview) {
        this.unitOfWork = unitOfWork;
        this.documents = documents;
        this.preview = preview;
    }

    public PreviewView draft(UUID surveyId, String locale) {
        return unitOfWork.transactional(() -> documents.draftDocument(surveyId)
                .map(document -> preview.preview(document, locale))
                .orElseThrow(() -> new NotFoundException("No survey exists here.")));
    }
}

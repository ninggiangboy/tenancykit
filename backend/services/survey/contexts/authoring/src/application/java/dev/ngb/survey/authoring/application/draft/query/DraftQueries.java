package dev.ngb.survey.authoring.application.draft.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The draft detail the API returns, read inside a tenant-scoped transaction. */
public class DraftQueries {

    private final UnitOfWork unitOfWork;
    private final DraftReadRepository drafts;

    public DraftQueries(UnitOfWork unitOfWork, DraftReadRepository drafts) {
        this.unitOfWork = unitOfWork;
        this.drafts = drafts;
    }

    public DraftView get(UUID surveyId) {
        return unitOfWork.transactional(() -> drafts.find(surveyId)
                .orElseThrow(() -> new NotFoundException("No survey exists here.")));
    }
}

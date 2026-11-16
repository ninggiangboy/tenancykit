package dev.ngb.survey.authoring.application.survey.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The survey listing and detail the API returns, read inside a tenant-scoped transaction. */
public class SurveyQueries {

    private final UnitOfWork unitOfWork;
    private final SurveyReadRepository surveys;

    public SurveyQueries(UnitOfWork unitOfWork, SurveyReadRepository surveys) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
    }

    public Page<SurveyView> list(ListQuery query) {
        return unitOfWork.transactional(() -> surveys.list(query));
    }

    public SurveyView get(UUID surveyId) {
        return unitOfWork.transactional(() -> surveys.find(surveyId)
                .orElseThrow(() -> new NotFoundException("No survey exists here.")));
    }
}

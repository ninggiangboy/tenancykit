package dev.ngb.survey.authoring.application.survey.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.PageSize;
import java.util.List;
import java.util.UUID;

/** The survey listing and detail the API returns, read inside a tenant-scoped transaction. */
public class SurveyQueries {

    /** The default page size of a listing; the contract has no paging parameter yet. */
    public static final int DEFAULT_LIMIT = 200;

    private final UnitOfWork unitOfWork;
    private final SurveyReadRepository surveys;

    public SurveyQueries(UnitOfWork unitOfWork, SurveyReadRepository surveys) {
        this.unitOfWork = unitOfWork;
        this.surveys = surveys;
    }

    public List<SurveyView> list() {
        return unitOfWork.transactional(() -> surveys.list(PageSize.of(DEFAULT_LIMIT)));
    }

    public SurveyView get(UUID surveyId) {
        return unitOfWork.transactional(() -> surveys.find(surveyId)
                .orElseThrow(() -> new NotFoundException("No survey exists here.")));
    }
}

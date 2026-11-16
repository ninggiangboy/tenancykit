package dev.ngb.survey.collection.application.link.service;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.survey.authoring.api.link.SurveyStateReader;
import java.util.UUID;

/**
 * The one question a link's creation asks of the authoring context: is this survey open?
 *
 * <p>It goes through authoring's {@code api} package rather than its tables, which is the seam the architecture rule
 * draws between the two contexts. It opens no transaction of its own: the use case that calls it already holds the
 * tenant-scoped one its reads depend on.
 */
public class SurveyState {

    private final SurveyStateReader surveys;

    public SurveyState(SurveyStateReader surveys) {
        this.surveys = surveys;
    }

    /** @throws NotFoundException when the tenant has no such survey, or one that is not open */
    public void requireOpen(UUID surveyId) {
        boolean open = surveys.isOpen(surveyId)
                .orElseThrow(() -> new NotFoundException("No survey exists here."));
        if (!open) {
            throw new dev.ngb.survey.collection.application.link.exception.LinkRefusedException(
                    dev.ngb.survey.collection.application.link.exception.LinkRefusedException.Reason.SURVEY_NOT_OPEN,
                    "the survey is not open, so a link would open nothing");
        }
    }
}

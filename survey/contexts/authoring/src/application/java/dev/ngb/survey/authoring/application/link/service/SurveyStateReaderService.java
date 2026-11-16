package dev.ngb.survey.authoring.application.link.service;

import dev.ngb.survey.authoring.api.link.SurveyStateReader;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers whether a survey is open, for the contexts that may not read the {@code surveys} table themselves.
 *
 * <p>It is a service, not a use case: it opens no transaction of its own because its caller already holds one, and the
 * write-side port it reads needs the caller's tenant scope.
 */
public class SurveyStateReaderService implements SurveyStateReader {

    private final SurveyRepository surveys;

    public SurveyStateReaderService(SurveyRepository surveys) {
        this.surveys = surveys;
    }

    @Override
    public Optional<Boolean> isOpen(UUID surveyId) {
        return surveys.findById(surveyId).map(survey -> survey.status() == SurveyStatus.OPEN);
    }
}

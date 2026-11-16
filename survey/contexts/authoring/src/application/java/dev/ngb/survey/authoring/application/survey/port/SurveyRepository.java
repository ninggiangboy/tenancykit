package dev.ngb.survey.authoring.application.survey.port;

import dev.ngb.survey.authoring.domain.survey.Survey;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's surveys: the row the lifecycle endpoints move. */
public interface SurveyRepository {

    void insert(Survey survey);

    Optional<Survey> findById(UUID surveyId);

    /**
     * Writes the row only when it still holds the version the caller read.
     *
     * @return false when another writer moved the row first
     */
    boolean update(Survey survey, long expectedVersion);
}

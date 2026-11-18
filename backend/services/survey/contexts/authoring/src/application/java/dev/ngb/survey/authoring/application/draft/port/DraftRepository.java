package dev.ngb.survey.authoring.application.draft.port;

import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's one draft per survey. */
public interface DraftRepository {

    void insert(SurveyDraft draft);

    Optional<SurveyDraft> findBySurveyId(UUID surveyId);

    /**
     * Replaces the draft only when it still holds the tag the caller read.
     *
     * @return false when another writer moved the tag first
     */
    boolean save(SurveyDraft draft, long expectedEtag, String updatedBy);
}

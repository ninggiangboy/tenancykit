package dev.ngb.survey.authoring.application.draft.query;

import java.util.Optional;
import java.util.UUID;

/** The read side of a survey's draft; row-level security restricts every query to the bound tenant. */
public interface DraftReadRepository {

    Optional<DraftView> find(UUID surveyId);
}

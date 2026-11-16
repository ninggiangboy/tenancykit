package dev.ngb.survey.authoring.application.survey.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's surveys; row-level security restricts every query to the bound tenant. */
public interface SurveyReadRepository {

    List<SurveyView> list(int limit);

    Optional<SurveyView> find(UUID surveyId);
}

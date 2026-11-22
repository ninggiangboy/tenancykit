package dev.ngb.survey.authoring.application.survey.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The read side of a tenant's surveys; row-level security restricts every query to the bound tenant. */
public interface SurveyReadRepository {

    Page<SurveyView> list(ListQuery query);

    Optional<SurveyView> find(UUID surveyId);
}

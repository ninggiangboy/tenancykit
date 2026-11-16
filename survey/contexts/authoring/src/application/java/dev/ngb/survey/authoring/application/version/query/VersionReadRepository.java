package dev.ngb.survey.authoring.application.version.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The read side of a survey's published versions; row-level security restricts every query to the bound tenant. */
public interface VersionReadRepository {

    Page<VersionView> list(ListQuery query, UUID surveyId);

    Optional<VersionView> find(UUID surveyId, int versionNo);
}

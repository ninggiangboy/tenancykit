package dev.ngb.survey.authoring.application.version.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The read side of a survey's published versions; row-level security restricts every query to the bound tenant. */
public interface VersionReadRepository {

    List<VersionView> list(UUID surveyId);

    Optional<VersionView> find(UUID surveyId, int versionNo);
}

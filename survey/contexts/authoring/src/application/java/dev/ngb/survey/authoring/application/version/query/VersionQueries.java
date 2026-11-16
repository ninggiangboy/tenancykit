package dev.ngb.survey.authoring.application.version.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** The version history and one version's document, read inside a tenant-scoped transaction. */
public class VersionQueries {

    private final UnitOfWork unitOfWork;
    private final VersionReadRepository versions;

    public VersionQueries(UnitOfWork unitOfWork, VersionReadRepository versions) {
        this.unitOfWork = unitOfWork;
        this.versions = versions;
    }

    public Page<VersionView> list(UUID surveyId, ListQuery query) {
        return unitOfWork.transactional(() -> versions.list(query, surveyId));
    }

    public VersionView get(UUID surveyId, int versionNo) {
        return unitOfWork.transactional(() -> versions.find(surveyId, versionNo)
                .orElseThrow(() -> new NotFoundException("No version exists here.")));
    }
}

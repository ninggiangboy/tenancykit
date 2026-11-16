package dev.ngb.survey.authoring.application.version.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** The version history and one version's document, read inside a tenant-scoped transaction. */
public class VersionQueries {

    private final UnitOfWork unitOfWork;
    private final VersionReadRepository versions;

    public VersionQueries(UnitOfWork unitOfWork, VersionReadRepository versions) {
        this.unitOfWork = unitOfWork;
        this.versions = versions;
    }

    public List<VersionView> list(UUID surveyId) {
        return unitOfWork.transactional(() -> versions.list(surveyId));
    }

    public VersionView get(UUID surveyId, int versionNo) {
        return unitOfWork.transactional(() -> versions.find(surveyId, versionNo)
                .orElseThrow(() -> new NotFoundException("No version exists here.")));
    }
}

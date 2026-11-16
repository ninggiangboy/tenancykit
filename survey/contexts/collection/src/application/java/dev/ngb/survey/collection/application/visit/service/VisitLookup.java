package dev.ngb.survey.collection.application.visit.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the visit a browser's token names into the aggregate a write use case needs, and the version it pinned.
 *
 * <p>It sits in the service role rather than the query role because its caller is about to change the visit — a draft,
 * an upload or a submit all move it — and the architecture rule keeps a read model from driving a write.
 */
public class VisitLookup {

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final PublishedVersionReader versions;

    public VisitLookup(UnitOfWork unitOfWork, VisitRepository visits, PublishedVersionReader versions) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.versions = versions;
    }

    /** The visit a presented token names, or empty when it names none in this tenant. */
    public Optional<Visit> byToken(byte[] tokenHash) {
        return unitOfWork.transactional(() -> visits.findByTokenHash(tokenHash));
    }

    /** The version document this visit pinned, which never changes even after a newer version is published. */
    public PinnedVersion versionOf(Visit visit) {
        return unitOfWork.transactional(() -> versions.byNumber(visit.surveyId(), visit.versionNo())
                .map(version -> new PinnedVersion(version.document(), version.versionNo()))
                .orElseThrow(() -> new dev.ngb.core.error.NotFoundException("No version exists here.")));
    }

    /** The document and the number a client reads, so a response records the version it answered. */
    public record PinnedVersion(Map<String, Object> document, int versionNo) {}
}

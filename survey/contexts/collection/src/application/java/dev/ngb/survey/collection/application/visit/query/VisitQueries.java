package dev.ngb.survey.collection.application.visit.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;

import java.util.Map;
import java.util.Optional;

/**
 * What a respondent's browser reads about its own visit: the version document it pinned and the number it was
 * published under.
 *
 * <p>Reading the pinned version rather than the current one is what makes "a respondent never sees questions change
 * under them" true: resuming a draft, reading the schema and the eventual submit all use the same number, so v2 only
 * reaches respondents who arrive after it.
 */
public class VisitQueries {

    private final UnitOfWork unitOfWork;
    private final VisitReadRepository reads;
    private final PublishedVersionReader versions;

    public VisitQueries(UnitOfWork unitOfWork, VisitReadRepository reads, PublishedVersionReader versions) {
        this.unitOfWork = unitOfWork;
        this.reads = reads;
        this.versions = versions;
    }

    /** The visit a presented token names, inside the caller's tenant scope. */
    public java.util.Optional<VisitView> byToken(byte[] tokenHash) {
        return unitOfWork.transactional(() -> reads.findByTokenHash(tokenHash));
    }

    /** The visit a token names, which the public endpoints require. */
    public VisitView requireByToken(byte[] tokenHash) {
        return byToken(tokenHash).orElseThrow(() -> new NotFoundException("No visit exists here."));
    }

    /** The version this visit pinned, at the number it opened with. */
    public VisitVersion versionOf(VisitView visit) {
        return unitOfWork.transactional(() -> versions.byNumber(visit.surveyId(), visit.versionNo())
                .map(version -> new VisitVersion(version.document(), version.versionNo()))
                .orElseThrow(() -> new NotFoundException("No version exists here.")));
    }

    /** The document and the number a client reads, so a response records the version it answered. */
    public record VisitVersion(Map<String, Object> document, int versionNo) {}
}

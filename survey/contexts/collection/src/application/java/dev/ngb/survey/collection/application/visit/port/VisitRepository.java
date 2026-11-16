package dev.ngb.survey.collection.application.visit.port;

import dev.ngb.survey.collection.domain.visit.Visit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of visits: opening one, moving it along, and recording a page view. */
public interface VisitRepository {

    void insert(Visit visit);

    Optional<Visit> findById(UUID visitId);

    /** Replaces the row only when it still holds this state, so two calls cannot both move it. */
    boolean save(Visit visit, String expectedState);

    void recordPageView(UUID id, UUID tenantId, UUID visitId, UUID pageId, Instant viewedAt);

    /** How many events this visit already holds, which is what bounds a client's reporting. */
    int eventCount(UUID visitId);

    /** Open visits that have been idle since before this instant, which is what the sweep abandons. */
    List<Visit> idleSince(Instant before, int limit);

    /** The visit an open visit of this personal link produced, if the link was already opened. */
    Optional<Visit> openVisitOfLink(UUID linkId);

    /** Whether this link already has a completed response, which a personal link may never have twice. */
    boolean completedForLink(UUID linkId);
}

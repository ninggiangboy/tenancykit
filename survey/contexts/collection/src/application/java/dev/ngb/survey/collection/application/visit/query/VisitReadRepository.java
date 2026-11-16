package dev.ngb.survey.collection.application.visit.query;

import java.util.Optional;
import java.util.UUID;

/**
 * The read side of visits, which the public surface and the page-view guard use.
 *
 * <p>It is separate from the write port on purpose: a read selects into a view and never drives a write, which is what
 * lets the architecture rule keep the two sides apart.
 */
public interface VisitReadRepository {

    /** The visit a presented token names, looked up by its hash. */
    Optional<VisitView> findByTokenHash(byte[] tokenHash);

    /** How many page events a visit already holds, which is what bounds a client's reporting. */
    int eventCount(UUID visitId);
}

package dev.ngb.booking.catalog.application.session.port;

import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores session series, the sessions they materialize and the pools of each session. Every statement is tenant-scoped
 * by row-level security, not by a condition here.
 *
 * <p>Inserting a session is idempotent on the pair of its series and its start, so a horizon that is expanded twice
 * inserts nothing the second time.
 */
public interface SessionRepository {

    void insertSeries(SessionSeries series);

    /** The series a conditional change writes, held under a row lock so two editors cannot interleave. */
    Optional<SessionSeries> lockSeriesById(UUID seriesId);

    void updateSeries(SessionSeries series);

    /** Every active series of the current tenant, which is what one materialization pass expands. */
    List<SessionSeries> activeSeries();

    /** @return true when this call inserted the session; false when the series already had one at that start */
    boolean insertSession(Session session);

    void insertPools(UUID sessionId, List<Pool> pools);

    /** Removes the future sessions a materialized series may be rebuilt from, keeping the cancelled ones as history. */
    void deleteFutureScheduledSessions(UUID seriesId, Instant from);

    Optional<Session> findSessionById(UUID sessionId);

    Optional<Session> lockSessionById(UUID sessionId);

    void markCancelled(Session session);

    /** Replaces a session's whole pool set with the one it now holds. */
    void replacePools(UUID sessionId, List<Pool> pools);

    /** The pools a series' sessions carry, ordered by code, or empty when it has no session yet. */
    List<Pool> poolsOfSeries(UUID seriesId);

    /**
     * Whether a session still holds places a customer is committed to.
     *
     * <p>No place exists yet, so this answers false; the phase that creates the places answers it from them.
     */
    boolean hasHeldOrBookedPlaces(UUID sessionId);
}

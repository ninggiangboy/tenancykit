package dev.ngb.booking.catalog.application.session.port;

import java.util.UUID;

/**
 * Creates the places of a session's pools.
 *
 * <p>Materializing a session inserts the session and its pools; the places themselves are a later phase's job, which
 * implements this port. Nothing calls it yet.
 */
public interface SessionPlaces {

    void createPlaces(UUID sessionId, String poolCode, int capacity);
}

package dev.ngb.booking.catalog.api.session;

import java.util.UUID;

/**
 * The places of a session's pools.
 *
 * <p>Materializing a session inserts the session and its pools; the places themselves belong to the capacity engine,
 * which implements this port. Catalog calls it inside the transaction that writes them, so a session, its pools and
 * their places are one write.
 *
 * <p>A resize moves a pool to a new number of places: a pool that grows gains free ones, and one that shrinks loses
 * free ones only. A target below the places a booking holds or has booked is refused rather than taken.
 */
public interface SessionPlaces {

    void createPlaces(UUID sessionId, String poolCode, int capacity);

    void resizePlaces(UUID sessionId, String poolCode, int capacity);

    /** Removes the pool's free places, leaving any a booking holds or has booked where they are. */
    void deletePlaces(UUID sessionId, String poolCode);

    /** How many of the pool's places are free. */
    int remaining(UUID sessionId, String poolCode);
}

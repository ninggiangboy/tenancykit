package dev.ngb.booking.inventory.application.capacity.port;

import java.util.UUID;

/**
 * Reads what a pool's places are doing, without claiming any of them: how many are free, how many exist, and how many
 * a booking holds or has booked.
 *
 * <p>The three counts come from the rows themselves rather than from a stored total, which is what keeps a read and a
 * claim from disagreeing.
 */
public interface CapacityUnitReader {

    /** How many places of the pool are free, which is never negative. */
    int remaining(UUID sessionId, String poolCode);

    /** How many places the pool has, free or not. */
    int total(UUID sessionId, String poolCode);

    /** How many places the pool has a booking hold on or has sold. */
    int committed(UUID sessionId, String poolCode);
}

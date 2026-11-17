package dev.ngb.booking.inventory.api.capacity;

import java.util.UUID;

/**
 * How many places of a session's pool are free, for a caller that needs to answer "is this target full?" without
 * claiming anything.
 */
public interface PoolAvailability {

    int remaining(UUID sessionId, String poolCode);
}

package dev.ngb.booking.inventory.api.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ranges a resource is already claimed for inside a window, from the claim rows that still hold. The caller's
 * tenant scope, not this method, narrows the answer.
 */
public interface BusyRangeReader {

    /** @return the claimed ranges of one resource that still block a new claim and intersect {@code [from, to)} */
    List<BusyRange> busy(UUID resourceId, Instant from, Instant to);
}

package dev.ngb.booking.catalog.api.calendar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What another context may read about a Tenant's calendar: a resource's opening pattern and exceptions, one offering's
 * selling rules, one group's members, and the ranges a resource is blocked for inside a window.
 *
 * <p>Availability belongs to inventory, but the data it subtracts belongs to the catalog, so this port is the only
 * door between them. Every method runs in the caller's tenant scope.
 */
public interface CalendarReader {

    Optional<ResourceCalendar> resource(UUID resourceId);

    Optional<OfferingCalendar> offering(UUID offeringId);

    Optional<GroupCalendar> group(UUID groupId);

    Optional<SessionCalendar> session(UUID sessionId);

    /** The closed ranges of one resource that intersect {@code [from, to)}, expanded from any recurring block. */
    List<BlockRange> blocks(UUID resourceId, Instant from, Instant to);
}

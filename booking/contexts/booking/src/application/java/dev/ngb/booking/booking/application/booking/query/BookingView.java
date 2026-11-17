package dev.ngb.booking.booking.application.booking.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One booking as a read returns it: the fields the API exposes, with the status and the source as text so the query
 * side never names the aggregate they belong to.
 */
public record BookingView(
        UUID id,
        String status,
        UUID offeringId,
        String offeringKind,
        String customerRef,
        int qty,
        String source,
        UUID sessionId,
        String poolCode,
        Instant startsAt,
        Instant endsAt,
        List<AssignedResourceView> resources,
        UUID seriesId,
        Instant holdExpiresAt,
        int extendedSeconds,
        Instant confirmedAt,
        Instant cancelledAt,
        String policyCode,
        long version) {

    public BookingView {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}

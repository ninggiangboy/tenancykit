package dev.ngb.booking.catalog.application.session.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One session as a read returns it, with the pools it offers places from. */
public record SessionView(
        UUID id,
        UUID offeringId,
        UUID seriesId,
        Instant startsAt,
        Instant endsAt,
        String status,
        int capacity,
        List<PoolView> pools) {

    public SessionView {
        pools = pools == null ? List.of() : List.copyOf(pools);
    }
}

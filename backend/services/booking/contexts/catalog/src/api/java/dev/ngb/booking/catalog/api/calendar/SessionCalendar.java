package dev.ngb.booking.catalog.api.calendar;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One capacity session as a booking reads it: what it sells, when it starts, and how many places each pool has. */
public record SessionCalendar(
        UUID sessionId,
        UUID offeringId,
        Instant startsAt,
        Instant endsAt,
        String status,
        int capacity,
        List<SessionPool> pools) {

    public SessionCalendar {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(offeringId, "offeringId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(status, "status");
        pools = List.copyOf(pools);
    }
}

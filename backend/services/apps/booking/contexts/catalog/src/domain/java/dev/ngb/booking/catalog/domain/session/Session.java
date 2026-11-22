package dev.ngb.booking.catalog.domain.session;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One occurrence a Tenant may sell places in: the two instants it runs between, how many places it offers and whether
 * it is still scheduled.
 *
 * <p>A session materialized from a series names it, and the pair of series and start instant is what makes a rerun of
 * the materialization insert nothing. A one-off session names no series. Cancelling keeps the row, so a booking that
 * referenced it still explains itself, and appends an exclusion to the series so the next expansion does not recreate
 * it.
 */
public record Session(
        SessionId id,
        TenantId tenantId,
        SessionSeriesId seriesId,
        UUID offeringId,
        Instant startsAt,
        Instant endsAt,
        SessionStatus status,
        int capacity,
        Instant createdAt,
        Instant updatedAt) {

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(offeringId, "offeringId");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(status, "status");
        if (capacity < 0) {
            throw new IllegalArgumentException("a session's capacity is at least 0");
        }
    }

    /** A session a series materialized: scheduled, and naming the series it came from. */
    public static Session materialized(
            SessionId id,
            TenantId tenantId,
            SessionSeriesId seriesId,
            UUID offeringId,
            Instant startsAt,
            Instant endsAt,
            int capacity,
            Instant now) {
        return new Session(
                id, tenantId, seriesId, offeringId, startsAt, endsAt, SessionStatus.SCHEDULED, capacity, now, now);
    }

    /** The same session, cancelled. */
    public Session cancelled(Instant now) {
        return new Session(
                id, tenantId, seriesId, offeringId, startsAt, endsAt, SessionStatus.CANCELLED, capacity, createdAt, now);
    }

    public boolean isCancelled() {
        return status == SessionStatus.CANCELLED;
    }

    /** Whether this occurrence came from a series, which is what an exclusion is appended to. */
    public boolean isFromSeries() {
        return seriesId != null;
    }
}

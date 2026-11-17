package dev.ngb.booking.booking.domain.series;

/** A series that still books its future occurrences, or one the Tenant cancelled. */
public enum SeriesStatus {
    ACTIVE,
    CANCELLED
}

package dev.ngb.booking.catalog.domain.session;

/** A session's lifecycle: one occurrence is scheduled until it is cancelled. */
public enum SessionStatus {
    SCHEDULED,
    CANCELLED
}

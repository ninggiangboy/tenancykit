package dev.ngb.booking.booking.domain.waitlist;

/** Where a waiting entry stands: still in line, offered a hold, accepted, or no longer waiting. */
public enum WaitlistStatus {
    WAITING,
    OFFERED,
    ACCEPTED,
    LAPSED,
    CANCELLED
}

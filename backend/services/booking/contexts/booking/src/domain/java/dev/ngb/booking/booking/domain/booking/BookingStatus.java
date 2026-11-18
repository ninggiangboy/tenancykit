package dev.ngb.booking.booking.domain.booking;

/**
 * Where a booking is in its lifecycle.
 *
 * <p>A booking is created {@code HELD} and exactly one transition leaves that state: {@code CONFIRMED} when the Tenant
 * committed to it, {@code CANCELLED} when it gave the place back, {@code EXPIRED} when nobody did either in time. A
 * confirmed booking is cancelled or marked a {@code NO_SHOW}; neither of those can be undone.
 */
public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    NO_SHOW
}

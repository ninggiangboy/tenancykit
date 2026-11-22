package dev.ngb.booking.booking.domain.booking;

/**
 * Who asked for the hold: the Tenant System, the customer-facing gate, or the waitlist worker.
 *
 * <p>It rides on every event, so a consumer can tell a place a customer took from a place a Tenant holds for them.
 */
public enum BookingSource {
    TENANT,
    GATE,
    WAITLIST
}

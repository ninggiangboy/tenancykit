package dev.ngb.booking.inventory.domain.capacity;

/**
 * What a place is doing: free, held by a booking whose hold may lapse, or committed to a booking.
 *
 * <p>Only {@code AVAILABLE} places are free, so the count a Tenant sees is exactly the rows in that state.
 */
public enum UnitStatus {
    AVAILABLE,
    HELD,
    BOOKED
}

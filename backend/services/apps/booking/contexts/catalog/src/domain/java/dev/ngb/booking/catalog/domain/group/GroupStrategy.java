package dev.ngb.booking.catalog.domain.group;

/** How a booking picks a member out of a group: by priority, in turn, by load, or to fit the seats a party needs. */
public enum GroupStrategy {
    PRIORITY,
    ROUND_ROBIN,
    LEAST_BUSY,
    FIT_SEATS
}

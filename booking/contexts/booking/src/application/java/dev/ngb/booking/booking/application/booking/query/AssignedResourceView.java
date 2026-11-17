package dev.ngb.booking.booking.application.booking.query;

import java.util.UUID;

/** One resource a booking claimed, with the offering requirement it fills, as a read returns it. */
public record AssignedResourceView(String slotCode, UUID resourceId) {}

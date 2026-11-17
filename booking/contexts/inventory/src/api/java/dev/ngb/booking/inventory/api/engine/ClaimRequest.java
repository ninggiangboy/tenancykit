package dev.ngb.booking.inventory.api.engine;

/** A claim request, in the shape the engine it is sent to understands. */
public sealed interface ClaimRequest permits IntervalClaim, CapacityClaim {}

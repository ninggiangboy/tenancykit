package dev.ngb.booking.inventory.api.engine;

/** The answer to a claim: it happened, or it did not and the caller is told why. */
public sealed interface ClaimOutcome permits Claimed, Conflict {}

package dev.ngb.booking.inventory.api.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * What a booking claims, and the one port both engines answer.
 *
 * <p>The capability is part of the contract rather than a runtime guess: the interval engine promises that no two
 * claims overlap, the capacity engine promises that a pool never holds or sells more places than it has. A caller
 * asks for the promise it needs.
 */
public interface InventoryEngine {

    /** The promise this engine makes. */
    Capability capability();

    /**
     * Claims what the request names, all or nothing.
     *
     * @return the claim, or a conflict that says why nothing was claimed
     */
    ClaimOutcome claim(ClaimRequest request);

    /** Turns a held claim into a committed one. */
    void confirm(UUID bookingId);

    /** Gives the claim back, so the place or the range is free again. */
    void release(UUID bookingId);

    /** Moves a held claim's expiry, which the booking lifecycle calls when a Tenant extends a hold. */
    void extend(UUID bookingId, Instant until);

    enum Capability {
        /** No two claims on a resource overlap. */
        INTERVAL_NO_OVERLAP,
        /** A pool's held plus booked places never exceed its capacity. */
        CAPACITY_FLASH_SALE
    }
}

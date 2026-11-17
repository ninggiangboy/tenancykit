package dev.ngb.booking.inventory.application.capacity.service;

import dev.ngb.booking.inventory.application.capacity.exception.CapacityInUseException;

/**
 * The numbers a place claim and a pool resize have to satisfy: a claim takes at least one place, a pool never shrinks
 * below what is held or booked, and a count of free places is never negative.
 */
public final class CapacityRules {

    /** @return the quantity, which is at least one */
    public int requireQty(int qty) {
        if (qty < 1) {
            throw new IllegalArgumentException("a claim takes at least one place");
        }
        return qty;
    }

    /**
     * @return the capacity the pool is sized to
     * @throws CapacityInUseException when the target is below the places already held or booked
     */
    public int requireResizeTarget(int target, int committed) {
        if (target < 0) {
            throw new IllegalArgumentException("a pool's capacity is at least 0");
        }
        if (target < committed) {
            throw new CapacityInUseException();
        }
        return target;
    }

    /** A count of free places, which no reading of the rows can make negative. */
    public int remaining(int free) {
        return Math.max(free, 0);
    }
}

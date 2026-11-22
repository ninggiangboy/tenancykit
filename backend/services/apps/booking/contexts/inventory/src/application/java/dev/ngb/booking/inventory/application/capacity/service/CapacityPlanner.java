package dev.ngb.booking.inventory.application.capacity.service;

/**
 * The arithmetic of resizing a pool: how many places to add, and how many free ones to take away.
 *
 * <p>A pool that grows gains the difference; one that shrinks loses difference rows, and only, ever, free ones, so a
 * shrink can never take a place a booking is holding or has booked.
 */
public final class CapacityPlanner {

    /** A resize split into the rows to insert and the free rows to delete. */
    public record Plan(int insert, int delete) {}

    private final CapacityRules rules;

    public CapacityPlanner(CapacityRules rules) {
        this.rules = rules;
    }

    /**
     * @param target how many places the pool should have
     * @param total how many places it has now
     * @param committed how many of them a booking holds or has booked
     */
    public Plan plan(int target, int total, int committed) {
        rules.requireResizeTarget(target, committed);
        int free = rules.remaining(total - committed);
        if (target > total) {
            return new Plan(target - total, 0);
        }
        if (target < total) {
            return new Plan(0, Math.min(total - target, free));
        }
        return new Plan(0, 0);
    }
}

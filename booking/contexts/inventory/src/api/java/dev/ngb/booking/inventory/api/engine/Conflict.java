package dev.ngb.booking.inventory.api.engine;

import java.util.Objects;

/**
 * A claim that did not happen.
 *
 * @param code a stable code such as {@code slot-taken} or {@code sold-out}
 * @param remaining how many places are still free, when the engine can say, else null
 */
public record Conflict(String code, String detail, Integer remaining) implements ClaimOutcome {

    public static final String SLOT_TAKEN = "slot-taken";

    public static final String SOLD_OUT = "sold-out";

    public Conflict {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (remaining != null && remaining < 0) {
            throw new IllegalArgumentException("a remaining count is never negative");
        }
    }
}

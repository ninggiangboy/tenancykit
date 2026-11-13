package dev.ngb.billing.pricing.domain.metric;

import java.math.BigDecimal;
import java.util.Objects;

/** What was used of one metric: the summed quantity and how many events it came from. */
public record Measure(BigDecimal quantity, long events) {

    public static final Measure NONE = new Measure(BigDecimal.ZERO, 0);

    public Measure {
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() < 0 || events < 0) {
            throw new IllegalArgumentException("a measure cannot be negative");
        }
    }

    /**
     * What one input reports of a metric. An omitted event count is one event for a positive quantity and none for
     * a zero one, so a count-based price counts a reported quantity and a usage price is unaffected.
     */
    public static Measure of(BigDecimal quantity, Long events) {
        return new Measure(quantity, events != null ? events : quantity.signum() > 0 ? 1 : 0);
    }

    /** Two inputs of the same metric add up: both their quantities and their events. */
    public Measure plus(Measure other) {
        return new Measure(quantity.add(other.quantity()), events + other.events());
    }
}

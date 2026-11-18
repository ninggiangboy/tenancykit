package dev.ngb.loyalty.program.api.tier;

/**
 * Where a tier starts and how much it multiplies a line's points. {@code minSpend} is qualifying spend in minor units of
 * the program currency; {@code position} is the place in the ordered list, lowest first.
 */
public record TierView(String code, String name, long minSpend, java.math.BigDecimal earnMultiplier, int position) {

    public TierView {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a tier code must not be blank");
        }
        if (minSpend < 0) {
            throw new IllegalArgumentException("minSpend must not be negative");
        }
        java.util.Objects.requireNonNull(earnMultiplier, "earnMultiplier");
    }
}

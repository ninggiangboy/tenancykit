package dev.ngb.loyalty.points.application.earn.service;

import dev.ngb.loyalty.program.api.tier.TierView;
import java.util.List;
import java.util.Optional;

/**
 * Which tier a member's lines multiply by.
 *
 * <p>The tier list is ordered lowest first, and a member without a tier of its own sits in the lowest one: qualifying
 * spend starts at nothing, so no member is tierless while a list exists. A program with no tiers multiplies by nothing
 * and every line earns at its promotion multiplier alone.
 */
public final class TierSelection {

    private TierSelection() {}

    /** The member's own tier, or the lowest one when it holds none; empty only when the program has no tiers. */
    public static Optional<TierView> current(String tierCode, List<TierView> tiers) {
        if (tiers.isEmpty()) {
            return Optional.empty();
        }
        if (tierCode != null) {
            for (TierView tier : tiers) {
                if (tier.code().equals(tierCode)) {
                    return Optional.of(tier);
                }
            }
        }
        return Optional.of(tiers.getFirst());
    }

    /** The tier one step above the member's, or empty when it is already the top one. */
    public static Optional<TierView> next(TierView current, List<TierView> tiers) {
        for (int i = 0; i + 1 < tiers.size(); i++) {
            if (tiers.get(i).code().equals(current.code())) {
                return Optional.of(tiers.get(i + 1));
            }
        }
        return Optional.empty();
    }
}

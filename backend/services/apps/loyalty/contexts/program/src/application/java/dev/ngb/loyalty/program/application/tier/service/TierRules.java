package dev.ngb.loyalty.program.application.tier.service;

import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules an ordered tier list obeys: at least one tier, the first one starting at zero, strictly increasing
 * thresholds, unique codes and a multiplier between 1.00 and 10.00 with at most two fraction digits.
 *
 * <p>The list is positional: a tier's place is its index, and a replace assigns the positions again.
 */
public final class TierRules {

    public static final BigDecimal MIN_MULTIPLIER = new BigDecimal("1.00");
    public static final BigDecimal MAX_MULTIPLIER = new BigDecimal("10.00");
    public static final int MAX_CODE = 100;
    public static final int MAX_NAME = 255;

    /** One tier a caller asked for, before it is given an identity and a place. */
    public record Input(String code, String name, Long minSpend, BigDecimal earnMultiplier) {}

    /** One validated tier, with its place in the list. */
    public record Entry(String code, String name, long minSpend, BigDecimal earnMultiplier, int position) {}

    private TierRules() {}

    public static List<Entry> validated(List<Input> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "The tier list needs at least one tier.");
        }
        List<Entry> entries = new ArrayList<>(requested.size());
        Set<String> codes = new HashSet<>();
        long previousMinSpend = -1;
        for (int position = 0; position < requested.size(); position++) {
            Input input = requested.get(position);
            if (input == null) {
                throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier entry must not be null.");
            }
            String code = code(input.code());
            if (!codes.add(code)) {
                throw ProgramRefusal.invalid("TIER_LIST_INVALID", "The tier code " + code + " appears twice.");
            }
            String name = name(input.name());
            long minSpend = minSpend(input.minSpend());
            if (position == 0 && minSpend != 0) {
                throw ProgramRefusal.invalid("TIER_LIST_INVALID", "The first tier starts at 0 qualifying spend.");
            }
            if (position > 0 && minSpend <= previousMinSpend) {
                throw ProgramRefusal.invalid(
                        "TIER_LIST_INVALID", "The tier thresholds must strictly increase; " + code + " does not.");
            }
            previousMinSpend = minSpend;
            entries.add(new Entry(code, name, minSpend, multiplier(input.earnMultiplier()), position));
        }
        return List.copyOf(entries);
    }

    private static String code(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_CODE) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier code is 1 to " + MAX_CODE + " characters.");
        }
        return value;
    }

    private static String name(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_NAME) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier name is 1 to " + MAX_NAME + " characters.");
        }
        return value;
    }

    private static long minSpend(Long raw) {
        if (raw == null || raw < 0) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier's minSpend is qualifying spend and cannot be negative.");
        }
        return raw;
    }

    private static BigDecimal multiplier(BigDecimal raw) {
        if (raw == null) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier's earnMultiplier is required.");
        }
        if (raw.stripTrailingZeros().scale() > 2) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier's earnMultiplier has at most two fraction digits.");
        }
        BigDecimal value = raw.setScale(2, RoundingMode.UNNECESSARY);
        if (value.compareTo(MIN_MULTIPLIER) < 0 || value.compareTo(MAX_MULTIPLIER) > 0) {
            throw ProgramRefusal.invalid("TIER_LIST_INVALID", "A tier's earnMultiplier is between 1.00 and 10.00.");
        }
        return value;
    }
}

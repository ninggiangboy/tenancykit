package dev.ngb.loyalty.program.application.promotion.service;

import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.domain.promotion.PromotionConditions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules a promotion obeys: a name, a multiplier between 1.01 and 10.00 with at most two fraction digits, a window
 * that does not end before it starts, and the closed condition set.
 *
 * <p>The condition set is closed on purpose. Several kinds mean all of them, several values inside one kind mean any of
 * them, and an empty list is no restriction; a new kind is a new field with a vector, never a new syntax.
 */
public final class PromotionRules {

    /** How many promotions of one tenant may be open (anything but archived) at once. */
    public static final int MAX_OPEN_PROMOTIONS = 200;

    public static final BigDecimal MIN_MULTIPLIER = new BigDecimal("1.01");
    public static final BigDecimal MAX_MULTIPLIER = new BigDecimal("10.00");
    public static final int MAX_NAME = 255;
    public static final int MAX_VALUES_PER_CONDITION = 200;
    public static final int MAX_CONDITION_VALUE = 100;

    private PromotionRules() {}

    /** @return the promotion's name */
    public static String name(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_NAME) {
            throw ProgramRefusal.invalid("name is 1 to " + MAX_NAME + " characters");
        }
        return value;
    }

    public static BigDecimal multiplier(BigDecimal raw) {
        if (raw == null) {
            throw ProgramRefusal.invalid("multiplier is required");
        }
        if (raw.stripTrailingZeros().scale() > 2) {
            throw ProgramRefusal.invalid("multiplier has at most two fraction digits");
        }
        BigDecimal value = raw.setScale(2, RoundingMode.UNNECESSARY);
        if (value.compareTo(MIN_MULTIPLIER) < 0 || value.compareTo(MAX_MULTIPLIER) > 0) {
            throw ProgramRefusal.invalid("multiplier is between 1.01 and 10.00");
        }
        return value;
    }

    /** @return the window, refused when it ends before it starts */
    public static Instant[] window(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw ProgramRefusal.invalid("the window ends before it starts");
        }
        return new Instant[] {startsAt, endsAt};
    }

    /** @return the resolved status; {@code active} is the wire shorthand for {@code ACTIVE} or {@code PAUSED} */
    public static PromotionStatus status(String status, Boolean active) {
        if (status != null && !status.isBlank()) {
            try {
                return PromotionStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ProgramRefusal.invalid("status is ACTIVE, PAUSED or ARCHIVED");
            }
        }
        if (active == null) {
            return PromotionStatus.ACTIVE;
        }
        return active ? PromotionStatus.ACTIVE : PromotionStatus.PAUSED;
    }

    /**
     * Normalizes the condition set: blank values are refused, duplicates collapse, and the time range is refused when
     * only one of its ends is present.
     */
    public static PromotionConditions conditions(PromotionConditions raw) {
        if (raw == null) {
            return PromotionConditions.NONE;
        }
        List<Integer> daysOfWeek = new ArrayList<>(new LinkedHashSet<>(
                raw.daysOfWeek() == null ? List.of() : raw.daysOfWeek()));
        for (Integer day : daysOfWeek) {
            if (day == null || day < 1 || day > 7) {
                throw ProgramRefusal.invalid("daysOfWeek holds ISO weekdays, 1 Monday to 7 Sunday");
            }
        }
        daysOfWeek.sort(Integer::compareTo);
        if ((raw.timeFrom() == null) != (raw.timeTo() == null)) {
            throw ProgramRefusal.invalid("timeFrom and timeTo are set together");
        }
        return new PromotionConditions(
                values("categories", raw.categories()),
                values("skus", raw.skus()),
                values("storeCodes", raw.storeCodes()),
                daysOfWeek,
                values("tierCodes", raw.tierCodes()),
                raw.timeFrom(),
                raw.timeTo());
    }

    /** @return the local time of day a request named, or null when it named none */
    public static LocalTime localTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw ProgramRefusal.invalid("a time of day is written as HH:mm, such as 18:30");
        }
    }

    private static List<String> values(String kind, List<String> raw) {
        Set<String> distinct = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                String trimmed = value == null ? "" : value.strip();
                if (trimmed.isEmpty() || trimmed.length() > MAX_CONDITION_VALUE) {
                    throw ProgramRefusal.invalid(kind + " holds values of 1 to " + MAX_CONDITION_VALUE + " characters");
                }
                distinct.add(trimmed);
            }
        }
        if (distinct.size() > MAX_VALUES_PER_CONDITION) {
            throw ProgramRefusal.invalid(kind + " holds at most " + MAX_VALUES_PER_CONDITION + " values");
        }
        return List.copyOf(distinct);
    }
}

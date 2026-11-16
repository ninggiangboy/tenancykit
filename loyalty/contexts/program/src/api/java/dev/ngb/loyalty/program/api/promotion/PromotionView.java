package dev.ngb.loyalty.program.api.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A promotion as the earn engine reads it: how much it multiplies, when its window is open, and the closed set of
 * conditions a line has to satisfy.
 *
 * <p>The conditions are a set of optional lists together with an optional local time range. Several kinds mean all of
 * them, several values inside one kind mean any of them, and an empty or missing list is no restriction. The day of the
 * week and the time of day are read in the program's {@code timeZone}, which every view carries.
 *
 * <p>{@code status} is included so an admin read can show a paused or archived promotion; only {@link PromotionStatus#ACTIVE}
 * ones reach the earn engine through {@link ProgramReader#activePromotionsAt}.
 */
public record PromotionView(
        java.util.UUID promotionId,
        String name,
        int priority,
        PromotionStatus status,
        BigDecimal multiplier,
        Instant startsAt,
        Instant endsAt,
        List<String> categories,
        List<String> skus,
        List<String> storeCodes,
        List<Integer> daysOfWeek,
        List<String> tierCodes,
        LocalTime timeFrom,
        LocalTime timeTo,
        ZoneId timeZone,
        long version) {

    public PromotionView {
        java.util.Objects.requireNonNull(promotionId, "promotionId");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(multiplier, "multiplier");
        java.util.Objects.requireNonNull(timeZone, "timeZone");
        categories = categories == null ? List.of() : List.copyOf(categories);
        skus = skus == null ? List.of() : List.copyOf(skus);
        storeCodes = storeCodes == null ? List.of() : List.copyOf(storeCodes);
        daysOfWeek = daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek);
        tierCodes = tierCodes == null ? List.of() : List.copyOf(tierCodes);
    }

    public boolean active() {
        return status == PromotionStatus.ACTIVE;
    }

    /** The same promotion evaluated in another zone, which is how a read stamps the program's zone onto its rows. */
    public PromotionView inZone(ZoneId zone) {
        return new PromotionView(promotionId, name, priority, status, multiplier, startsAt, endsAt, categories, skus,
                storeCodes, daysOfWeek, tierCodes, timeFrom, timeTo, zone, version);
    }

    /** Whether this promotion applies to the line: its window is open and every condition kind that is set matches. */
    public boolean matches(PromotionMatch line) {
        if (startsAt != null && line.at().isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && line.at().isAfter(endsAt)) {
            return false;
        }
        if (restricts(categories) && !categories.contains(line.category())) {
            return false;
        }
        if (restricts(skus) && !skus.contains(line.sku())) {
            return false;
        }
        if (restricts(storeCodes) && !storeCodes.contains(line.storeCode())) {
            return false;
        }
        if (restricts(tierCodes) && !tierCodes.contains(line.tierCode())) {
            return false;
        }
        ZonedDateTime local = line.at().atZone(timeZone);
        if (!daysOfWeek.isEmpty() && !daysOfWeek.contains(local.getDayOfWeek().getValue())) {
            return false;
        }
        return matchesTime(local.toLocalTime());
    }

    /**
     * A range whose end is before its start wraps midnight, which is how a late-evening window is expressed.
     */
    private boolean matchesTime(LocalTime time) {
        if (timeFrom == null && timeTo == null) {
            return true;
        }
        if (timeFrom == null) {
            return !time.isAfter(timeTo);
        }
        if (timeTo == null) {
            return !time.isBefore(timeFrom);
        }
        if (timeTo.isBefore(timeFrom)) {
            return !time.isBefore(timeFrom) || !time.isAfter(timeTo);
        }
        return !time.isBefore(timeFrom) && !time.isAfter(timeTo);
    }

    private static boolean restricts(List<String> values) {
        return values != null && !values.isEmpty();
    }
}

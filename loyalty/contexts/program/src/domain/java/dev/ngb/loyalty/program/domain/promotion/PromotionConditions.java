package dev.ngb.loyalty.program.domain.promotion;

import java.time.LocalTime;
import java.util.List;

/**
 * The closed set of conditions a promotion may carry: five lists and an optional local time range. Several kinds mean
 * all of them, several values inside one kind mean any of them, and an empty list is no restriction.
 *
 * <p>{@code daysOfWeek} holds ISO weekdays, 1 Monday to 7 Sunday. {@code timeFrom} and {@code timeTo} are local times in
 * the program's zone; a range whose end is before its start wraps midnight.
 */
public record PromotionConditions(
        List<String> categories,
        List<String> skus,
        List<String> storeCodes,
        List<Integer> daysOfWeek,
        List<String> tierCodes,
        LocalTime timeFrom,
        LocalTime timeTo) {

    /** No condition at all: the promotion applies to every line in its window. */
    public static final PromotionConditions NONE =
            new PromotionConditions(List.of(), List.of(), List.of(), List.of(), List.of(), null, null);

    public PromotionConditions {
        categories = copy(categories);
        skus = copy(skus);
        storeCodes = copy(storeCodes);
        tierCodes = copy(tierCodes);
        daysOfWeek = daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek);
    }

    public boolean isEmpty() {
        return categories.isEmpty()
                && skus.isEmpty()
                && storeCodes.isEmpty()
                && daysOfWeek.isEmpty()
                && tierCodes.isEmpty()
                && timeFrom == null
                && timeTo == null;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

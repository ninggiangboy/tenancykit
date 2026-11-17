package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** The metrics a report may ask for. */
public enum Metric {
    CLICKS,
    PAGE_VIEWS,
    SESSIONS,
    VISITOR_DAYS,
    CONVERSIONS,
    CREDITED_VALUE;

    public static Metric named(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "clicks" -> CLICKS;
            case "page_views", "pageviews" -> PAGE_VIEWS;
            case "sessions" -> SESSIONS;
            case "visitor_days", "unique_visitor_days", "uniques" -> VISITOR_DAYS;
            case "conversions" -> CONVERSIONS;
            case "credited_value", "value" -> CREDITED_VALUE;
            default -> throw new BadRequestException("unknown metric: " + value);
        };
    }

    public boolean conversionMetric() {
        return this == CONVERSIONS || this == CREDITED_VALUE;
    }

    public static Set<Metric> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of(CLICKS);
        }
        Set<Metric> metrics = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                metrics.add(named(part.strip()));
            }
        }
        return metrics.isEmpty() ? Set.of(CLICKS) : Set.copyOf(metrics);
    }
}

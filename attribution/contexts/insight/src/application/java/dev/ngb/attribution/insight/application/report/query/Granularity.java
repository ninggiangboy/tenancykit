package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.util.Locale;

/** The bucket a series is reported in. Conversion metrics are daily only. */
public enum Granularity {
    HOUR,
    DAY;

    public static Granularity named(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "hour" -> HOUR;
            case "day" -> DAY;
            default -> throw new BadRequestException("unknown granularity: " + value);
        };
    }
}

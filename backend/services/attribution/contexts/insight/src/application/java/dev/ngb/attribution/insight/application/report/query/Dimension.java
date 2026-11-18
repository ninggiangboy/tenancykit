package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.core.error.BadRequestException;
import java.util.Locale;

/**
 * The dimensions a breakdown can group by.
 *
 * <p>Traffic dimensions come from the hourly rollup; {@code GOAL} comes from the daily conversion rollup; {@code REFERRER}
 * comes from its own daily rollup and offers clicks only. A metric a dimension cannot answer is refused rather than
 * silently returned as zero.
 */
public enum Dimension {
    LINK,
    CAMPAIGN,
    ENTRY,
    DEVICE,
    COUNTRY,
    REFERRER,
    DESTINATION,
    GOAL;

    public static Dimension named(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("a breakdown needs a dimension");
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "link" -> LINK;
            case "campaign" -> CAMPAIGN;
            case "entry" -> ENTRY;
            case "device" -> DEVICE;
            case "country" -> COUNTRY;
            case "referrer" -> REFERRER;
            case "destination" -> DESTINATION;
            case "goal" -> GOAL;
            default -> throw new BadRequestException("unknown dimension: " + value);
        };
    }

    public boolean traffic() {
        return this != GOAL;
    }

    public boolean conversions() {
        return this == LINK || this == CAMPAIGN || this == GOAL;
    }

    /** Whether the dimension can answer page views, sessions and uniques, not only clicks. */
    public boolean fullTraffic() {
        return traffic() && this != REFERRER;
    }
}

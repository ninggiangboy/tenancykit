package dev.ngb.survey.insight.application.funnel.query;

import java.util.List;
import java.util.Map;

/**
 * The funnel a Tenant reads: how many people opened a link, started answering, completed and stopped, and where they
 * stopped.
 *
 * <p>Opened, started and completed are server-side facts; only the page a respondent last viewed depends on the client
 * reporting it, so the abandonment page is approximate and the plan says so. Bot visits are excluded from every row,
 * because a link preview is not a respondent.
 */
public record FunnelView(
        String groupBy,
        List<Row> rows,
        long opened,
        long started,
        long completed,
        long abandoned) {

    /**
     * One group's counts.
     *
     * @param key the source, country or device class the row is grouped by; null when the visit carried none
     * @param lastPage the page most abandoned visits in this group stopped on, or null when none did
     */
    public record Row(
            String key,
            long opened,
            long started,
            long completed,
            long abandoned,
            String lastPage) {}

    /** The groups a funnel can be split by. */
    public enum GroupBy {
        SOURCE,
        COUNTRY,
        DEVICE_CLASS;

        /** The grouping a query parameter names, or {@link #SOURCE} when it names none. */
        public static GroupBy named(String value) {
            if (value == null || value.isBlank()) {
                return SOURCE;
            }
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "country" -> COUNTRY;
                case "device_class", "device" -> DEVICE_CLASS;
                default -> SOURCE;
            };
        }
    }

    /** The counts of one group, keyed by the group's value. */
    public record Counts(long opened, long started, long completed, long abandoned) {}

    /** A group's counts plus the page most of its abandoned visits stopped on. */
    public static Map<String, Counts> empty() {
        return Map.of();
    }
}

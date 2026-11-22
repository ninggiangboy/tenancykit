package dev.ngb.attribution.insight.application.report.query;

import java.util.UUID;

/**
 * The filters a report narrows by. Only the ones a rollup can answer are applied: a traffic rollup carries link,
 * campaign, destination, entry, device and country; a conversion rollup carries link, campaign and goal.
 */
public record ReportFilter(
        UUID linkId,
        UUID campaignId,
        UUID destinationId,
        UUID goalId,
        String entry,
        String device,
        String country,
        String referrer) {

    public static ReportFilter empty() {
        return new ReportFilter(null, null, null, null, null, null, null, null);
    }

    public boolean hasTrafficFilter() {
        return linkId != null || campaignId != null || destinationId != null || entry != null
                || device != null || country != null;
    }

    public boolean hasConversionFilter() {
        return linkId != null || campaignId != null || goalId != null;
    }
}

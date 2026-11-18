package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The credits of one campaign on one day and currency, read from the conversion rollup for the cost report.
 *
 * <p>The currency is the conversion's; a cost report keeps a row whose currency differs from the campaign's out of the
 * value metrics and counts it as excluded.
 */
public record CampaignDayConversions(
        LocalDate day,
        UUID campaignId,
        String currency,
        BigDecimal conversions,
        BigDecimal creditedValue,
        BigDecimal multiTouchConversions) {}

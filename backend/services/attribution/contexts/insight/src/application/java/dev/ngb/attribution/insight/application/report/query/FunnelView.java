package dev.ngb.attribution.insight.application.report.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The funnel: non-bot clicks, the clicks a page view landed, and the clicks that received a conversion credit.
 *
 * @param landedRate {@code landed / clicks}, or null when there were no clicks
 * @param convertedRate {@code converted / landed}, or null when nothing landed
 * @param botClicksExcluded the bot and suspect clicks in the window, which the funnel's first step left out
 */
public record FunnelView(
        String entity,
        UUID entityId,
        UUID goalId,
        Instant from,
        Instant to,
        String trafficClass,
        long clicks,
        long landed,
        long converted,
        BigDecimal landedRate,
        BigDecimal convertedRate,
        long botClicksExcluded,
        Instant dataThrough) {}

package dev.ngb.attribution.insight.application.report.query;

import java.time.Instant;
import java.util.UUID;

/** One funnel request: an entity (a campaign or a link), an optional goal and a click window. */
public record FunnelQuery(
        Instant from, Instant to, UUID campaignId, UUID linkId, UUID goalId, TrafficClassFilter traffic) {}

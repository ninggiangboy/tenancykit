package dev.ngb.attribution.insight.application.report.query;

import java.time.Instant;
import java.util.UUID;

/** One cohort request: an owning campaign, a window and at most twelve week columns. */
public record CohortQuery(UUID campaignId, Instant from, Instant to, int periods) {}

package dev.ngb.attribution.insight.application.report.query;

import java.time.LocalDate;
import java.util.UUID;

/** Human clicks of one campaign on one day, read from the traffic rollup for the cost report. */
public record CampaignDayClicks(LocalDate day, UUID campaignId, long clicks) {}

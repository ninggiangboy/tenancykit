package dev.ngb.attribution.insight.application.report.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The report read side: series, breakdowns, the funnel, cohorts, link statistics and the cost report's inputs. */
public interface ReportReadRepository {

    TimeSeriesView timeseries(ReportQuery query, Instant dataThrough);

    BreakdownView breakdown(ReportQuery query, Dimension dimension, Instant dataThrough);

    FunnelView funnel(FunnelQuery query, Instant dataThrough);

    CohortView cohorts(CohortQuery query, Instant dataThrough);

    LinkStatsView linkStats(UUID linkId, String status, Long maxClicks, Instant now, Instant dataThrough);

    /** Human clicks of each campaign and day, read from the traffic rollup for the cost report. */
    List<CampaignDayClicks> clicksByCampaignDay(ReportRange range, UUID campaignId);

    /** Credits of each campaign, day and currency, read from the conversion rollup for the cost report. */
    List<CampaignDayConversions> conversionsByCampaignDay(ReportRange range, UUID campaignId);
}

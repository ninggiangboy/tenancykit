package dev.ngb.attribution.insight.application.report.query;

import dev.ngb.attribution.insight.api.rollup.RollupStateReader;
import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.spend.api.cost.CostReader;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The report endpoints, read in one tenant-scoped transaction each.
 *
 * <p>A report is assembled from the rollups insight owns; the funnel asks tracking and attribution for their raw counts
 * through their api packages, and the cost report adds spend through its api. The service is where the pieces meet, so
 * the adapter stays one query shape per question.
 */
public class ReportQueries {

    private static final int MAX_COHORT_PERIODS = 12;
    private static final int RATIO_SCALE = 4;

    private final UnitOfWork unitOfWork;
    private final ReportReadRepository reports;
    private final RollupStateReader state;
    private final CostReader costs;
    private final CampaignPolicyReader campaigns;
    private final dev.ngb.attribution.links.api.link.LinkMetadataReader links;

    public ReportQueries(
            UnitOfWork unitOfWork,
            ReportReadRepository reports,
            RollupStateReader state,
            CostReader costs,
            CampaignPolicyReader campaigns,
            dev.ngb.attribution.links.api.link.LinkMetadataReader links) {
        this.unitOfWork = unitOfWork;
        this.reports = reports;
        this.state = state;
        this.costs = costs;
        this.campaigns = campaigns;
        this.links = links;
    }

    public TimeSeriesView timeseries(ReportQuery query) {
        return unitOfWork.transactional(() -> reports.timeseries(query, state.dataThrough()));
    }

    public BreakdownView breakdown(ReportQuery query, Dimension dimension) {
        if (dimension == Dimension.GOAL && query.metrics().stream().anyMatch(metric -> !metric.conversionMetric())) {
            throw new BadRequestException("a goal breakdown reports conversions and credited value only");
        }
        if (dimension != Dimension.GOAL && query.metrics().stream().anyMatch(Metric::conversionMetric)
                && !dimension.conversions()) {
            throw new BadRequestException(dimension.name().toLowerCase(java.util.Locale.ROOT)
                    + " does not carry conversion metrics");
        }
        if (!dimension.fullTraffic() && query.metrics().stream().anyMatch(metric -> metric != Metric.CLICKS
                && !metric.conversionMetric())) {
            throw new BadRequestException(dimension.name().toLowerCase(java.util.Locale.ROOT)
                    + " reports clicks only");
        }
        return unitOfWork.transactional(() -> reports.breakdown(query, dimension, state.dataThrough()));
    }

    public FunnelView funnel(FunnelQuery query) {
        if (query.campaignId() == null && query.linkId() == null) {
            throw new BadRequestException("a funnel needs a campaign_id or a link_id");
        }
        return unitOfWork.transactional(() -> reports.funnel(query, state.dataThrough()));
    }

    public CohortView cohorts(CohortQuery query) {
        if (query.campaignId() == null) {
            throw new BadRequestException("a cohort needs a campaign_id");
        }
        if (query.periods() < 0 || query.periods() > MAX_COHORT_PERIODS) {
            throw new BadRequestException("periods is between 0 and " + MAX_COHORT_PERIODS);
        }
        return unitOfWork.transactional(() -> reports.cohorts(query, state.dataThrough()));
    }

    public LinkStatsView linkStats(UUID linkId, Instant now) {
        return unitOfWork.transactional(() -> {
            var link = links.link(linkId).orElseThrow(() -> new NotFoundException("No link exists here."));
            return reports.linkStats(linkId, link.status(), link.maxClicks(), now, state.dataThrough());
        });
    }

    /**
     * The cost report: cost per campaign and day next to the human clicks and credited conversions, grouped by campaign
     * or by day.
     *
     * @param groupBy {@code campaign} or {@code day}
     */
    public CostReportView costs(ReportRange range, UUID campaignId, String groupBy) {
        String grouping = groupBy == null || groupBy.isBlank() ? "campaign" : groupBy.strip().toLowerCase(java.util.Locale.ROOT);
        if (!grouping.equals("campaign") && !grouping.equals("day")) {
            throw new BadRequestException("group_by is campaign or day");
        }
        return unitOfWork.transactional(() -> {
            List<CostReader.CostRow> costRows =
                    costs.costs(range.fromDay(), range.toDayExclusive(), campaignId);
            List<CampaignDayClicks> clickRows = reports.clicksByCampaignDay(range, campaignId);
            List<CampaignDayConversions> conversionRows = reports.conversionsByCampaignDay(range, campaignId);

            Map<UUID, String> currencies = new LinkedHashMap<>();
            Map<GroupKey, MutableRow> rows = new LinkedHashMap<>();
            for (CostReader.CostRow cost : costRows) {
                MutableRow row = rows.computeIfAbsent(
                        GroupKey.of(grouping, cost.campaignId(), cost.day()),
                        key -> new MutableRow(key, currency(campaigns, currencies, cost.campaignId())));
                row.cost = row.cost.add(cost.amount());
                row.sources.merge(cost.source(), cost.amount(), BigDecimal::add);
            }
            for (CampaignDayClicks clicks : clickRows) {
                MutableRow row = rows.computeIfAbsent(
                        GroupKey.of(grouping, clicks.campaignId(), clicks.day()),
                        key -> new MutableRow(key, currency(campaigns, currencies, clicks.campaignId())));
                row.humanClicks += clicks.clicks();
            }
            for (CampaignDayConversions conversions : conversionRows) {
                MutableRow row = rows.computeIfAbsent(
                        GroupKey.of(grouping, conversions.campaignId(), conversions.day()),
                        key -> new MutableRow(key, currency(campaigns, currencies, conversions.campaignId())));
                if (row.currency == null || row.currency.equals(conversions.currency())) {
                    row.conversions = row.conversions.add(conversions.conversions());
                    row.creditedValue = row.creditedValue.add(conversions.creditedValue());
                } else {
                    row.excludedConversions = row.excludedConversions.add(conversions.conversions());
                }
            }

            List<CostReportView.Row> built = new ArrayList<>(rows.size());
            rows.values().forEach(row -> built.add(row.toRow()));
            built.sort(Comparator.comparing(CostReportView.Row::key));
            return new CostReportView(grouping, range.fromDay(), range.toDayExclusive().minusDays(1), built,
                    state.dataThrough());
        });
    }

    private static String currency(
            CampaignPolicyReader campaigns, Map<UUID, String> cache, UUID campaignId) {
        if (campaignId == null) {
            return null;
        }
        return cache.computeIfAbsent(campaignId, id -> campaigns.policy(id)
                .map(CampaignPolicyReader.Policy::currency)
                .orElse(null));
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private record GroupKey(String grouping, UUID campaignId, LocalDate day) {

        static GroupKey of(String grouping, UUID campaignId, LocalDate day) {
            return grouping.equals("day") ? new GroupKey(grouping, null, day) : new GroupKey(grouping, campaignId, null);
        }

        String label() {
            return day != null ? day.toString() : String.valueOf(campaignId);
        }
    }

    private static final class MutableRow {

        private final GroupKey key;
        private final String currency;
        private BigDecimal cost = BigDecimal.ZERO;
        private long humanClicks;
        private BigDecimal conversions = BigDecimal.ZERO;
        private BigDecimal creditedValue = BigDecimal.ZERO;
        private BigDecimal excludedConversions = BigDecimal.ZERO;
        private final Map<String, BigDecimal> sources = new LinkedHashMap<>();

        private MutableRow(GroupKey key, String currency) {
            this.key = key;
            this.currency = currency;
        }

        private CostReportView.Row toRow() {
            BigDecimal clickCount = BigDecimal.valueOf(humanClicks);
            BigDecimal roi = cost.signum() == 0
                    ? null
                    : creditedValue.subtract(cost).divide(cost, RATIO_SCALE, RoundingMode.HALF_UP);
            return new CostReportView.Row(
                    key.label(),
                    key.campaignId(),
                    key.day(),
                    cost,
                    humanClicks,
                    conversions,
                    creditedValue,
                    ratio(cost, clickCount),
                    ratio(cost, conversions),
                    ratio(creditedValue, cost),
                    roi,
                    excludedConversions,
                    sources.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new CostReportView.SourceCost(entry.getKey(), entry.getValue()))
                            .toList());
        }
    }
}

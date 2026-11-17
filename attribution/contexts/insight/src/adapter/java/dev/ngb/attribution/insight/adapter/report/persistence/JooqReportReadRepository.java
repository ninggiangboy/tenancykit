package dev.ngb.attribution.insight.adapter.report.persistence;

import static dev.ngb.attribution_jooq.Tables.ROLLUP_CONVERSIONS_DAILY;
import static dev.ngb.attribution_jooq.Tables.ROLLUP_REFERRERS_DAILY;
import static dev.ngb.attribution_jooq.Tables.ROLLUP_TRAFFIC_HOURLY;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import dev.ngb.attribution.insight.application.report.query.BreakdownView;
import dev.ngb.attribution.insight.application.report.query.CampaignDayClicks;
import dev.ngb.attribution.insight.application.report.query.CampaignDayConversions;
import dev.ngb.attribution.insight.application.report.query.CohortQuery;
import dev.ngb.attribution.insight.application.report.query.CohortView;
import dev.ngb.attribution.insight.application.report.query.Dimension;
import dev.ngb.attribution.insight.application.report.query.FunnelQuery;
import dev.ngb.attribution.insight.application.report.query.FunnelView;
import dev.ngb.attribution.insight.application.report.query.Granularity;
import dev.ngb.attribution.insight.application.report.query.LinkStatsView;
import dev.ngb.attribution.insight.application.report.query.Metric;
import dev.ngb.attribution.insight.application.report.query.ReportFilter;
import dev.ngb.attribution.insight.application.report.query.ReportQuery;
import dev.ngb.attribution.insight.application.report.query.ReportRange;
import dev.ngb.attribution.insight.application.report.query.ReportReadRepository;
import dev.ngb.attribution.insight.application.report.query.TimeSeriesView;
import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The report queries over the rollups.
 *
 * <p>Traffic and conversions are aggregated separately and merged by bucket or dimension key, because they live in
 * different tables with different granularity: traffic is hourly, conversions are daily. A metric the caller did not ask
 * for stays null, so a client can tell "not requested" from "measured zero".
 *
 * <p>The funnel is the one report that reads raw events, through tracking's api, because it needs a click id; the cohort
 * reads the snapshotted chains through attribution's api. Neither reaches into another context's tables.
 */
public class JooqReportReadRepository implements ReportReadRepository {

    private static final int RATIO_SCALE = 4;

    private final DSLContext dsl;
    private final TrafficSource traffic;
    private final ConversionRollupSource conversions;

    public JooqReportReadRepository(DSLContext dsl, TrafficSource traffic, ConversionRollupSource conversions) {
        this.dsl = dsl;
        this.traffic = traffic;
        this.conversions = conversions;
    }

    @Override
    public TimeSeriesView timeseries(ReportQuery query, Instant dataThrough) {
        boolean wantsTraffic = query.metrics().stream().anyMatch(metric -> !metric.conversionMetric());
        TreeMap<Instant, MutablePoint> points = new TreeMap<>();
        if (wantsTraffic) {
            trafficSeries(query, points);
        }
        if (query.metrics().stream().anyMatch(Metric::conversionMetric)) {
            conversionSeries(query, points);
        }
        List<TimeSeriesView.Point> built = points.values().stream()
                .map(point -> point.toPoint(query.metrics()))
                .toList();
        long botClicks = query.traffic().humanOnly() ? botClicks(query.range(), query.filter()) : 0;
        return new TimeSeriesView(
                query.granularity().name().toLowerCase(Locale.ROOT), query.range().from(), query.range().to(),
                query.traffic().label(), built, dataThrough, botClicks);
    }

    private void trafficSeries(ReportQuery query, Map<Instant, MutablePoint> points) {
        Field<OffsetDateTime> bucket = bucketField(query.granularity());
        dsl.select(
                        bucket.as("bucket"),
                        clicksSum().as("clicks"),
                        pageViewsSum().as("page_views"),
                        sessionsSum().as("sessions"),
                        visitorDaysSum().as("visitors"))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(trafficCondition(query.range(), query.filter(), query.traffic()))
                .groupBy(bucket)
                .orderBy(bucket.asc())
                .fetch()
                .forEach(row -> {
                    MutablePoint point = points.computeIfAbsent(
                            row.get("bucket", OffsetDateTime.class).toInstant(), MutablePoint::new);
                    point.clicks = row.get("clicks", Long.class);
                    point.pageViews = row.get("page_views", Long.class);
                    point.sessions = row.get("sessions", Long.class);
                    point.visitorDays = row.get("visitors", Long.class);
                });
    }

    private void conversionSeries(ReportQuery query, Map<Instant, MutablePoint> points) {
        dsl.select(
                        ROLLUP_CONVERSIONS_DAILY.DAY,
                        conversionsSum().as("conversions"),
                        creditedValueSum().as("credited"),
                        multiTouchSum().as("multi_touch"))
                .from(ROLLUP_CONVERSIONS_DAILY)
                .where(conversionCondition(query.range(), query.filter()))
                .groupBy(ROLLUP_CONVERSIONS_DAILY.DAY)
                .orderBy(ROLLUP_CONVERSIONS_DAILY.DAY.asc())
                .fetch()
                .forEach(row -> {
                    Instant bucket = startOf(row.get(ROLLUP_CONVERSIONS_DAILY.DAY));
                    MutablePoint point = points.computeIfAbsent(bucket, MutablePoint::new);
                    point.conversions = row.get("conversions", BigDecimal.class);
                    point.creditedValue = row.get("credited", BigDecimal.class);
                    point.multiTouch = row.get("multi_touch", BigDecimal.class);
                });
    }

    @Override
    public BreakdownView breakdown(ReportQuery query, Dimension dimension, Instant dataThrough) {
        Map<String, MutableRow> rows = new LinkedHashMap<>();
        if (dimension.traffic()) {
            if (dimension == Dimension.REFERRER) {
                referrerBreakdown(query, rows);
            } else {
                trafficBreakdown(query, dimension, rows);
            }
        }
        if (dimension.conversions() && query.metrics().stream().anyMatch(Metric::conversionMetric)) {
            conversionBreakdown(query, dimension, rows);
        }
        long botClicks = query.traffic().humanOnly()
                ? botClicks(query.range(), query.filter())
                : 0;
        List<BreakdownView.Row> built = new ArrayList<>(rows.values().stream()
                .map(row -> row.toRow(query.metrics()))
                .toList());
        built.sort(Comparator
                .comparing((BreakdownView.Row row) -> row.clicks() == null ? row.conversions() : BigDecimal.valueOf(row.clicks()))
                .reversed()
                .thenComparing(BreakdownView.Row::key));
        return new BreakdownView(
                dimension.name().toLowerCase(Locale.ROOT), query.range().from(), query.range().to(),
                query.traffic().label(), built, botClicks, dataThrough);
    }

    private void trafficBreakdown(ReportQuery query, Dimension dimension, Map<String, MutableRow> rows) {
        Field<?> key = trafficDimension(dimension);
        dsl.select(
                        key.as("key"),
                        clicksSum().as("clicks"),
                        pageViewsSum().as("page_views"),
                        sessionsSum().as("sessions"),
                        visitorDaysSum().as("visitors"))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(trafficCondition(query.range(), query.filter(), query.traffic()))
                .groupBy(key)
                .fetch()
                .forEach(row -> {
                    MutableRow target = rows.computeIfAbsent(keyOf(row.get("key")), MutableRow::new);
                    target.clicks = row.get("clicks", Long.class);
                    target.pageViews = row.get("page_views", Long.class);
                    target.sessions = row.get("sessions", Long.class);
                    target.visitorDays = row.get("visitors", Long.class);
                });
    }

    private void referrerBreakdown(ReportQuery query, Map<String, MutableRow> rows) {
        Condition where = ROLLUP_REFERRERS_DAILY.DAY.ge(query.range().fromDay())
                .and(ROLLUP_REFERRERS_DAILY.DAY.lt(query.range().toDayExclusive()));
        if (query.filter().linkId() != null) {
            where = where.and(ROLLUP_REFERRERS_DAILY.LINK_ID.eq(query.filter().linkId()));
        }
        dsl.select(
                        ROLLUP_REFERRERS_DAILY.REFERRER_DOMAIN.as("key"),
                        DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_REFERRERS_DAILY.CLICKS).as("clicks"))
                .from(ROLLUP_REFERRERS_DAILY)
                .where(where)
                .groupBy(ROLLUP_REFERRERS_DAILY.REFERRER_DOMAIN)
                .fetch()
                .forEach(row -> {
                    MutableRow target = rows.computeIfAbsent(keyOf(row.get("key")), MutableRow::new);
                    target.clicks = row.get("clicks", Long.class);
                });
    }

    private void conversionBreakdown(ReportQuery query, Dimension dimension, Map<String, MutableRow> rows) {
        Field<?> key = conversionDimension(dimension);
        dsl.select(
                        key.as("key"),
                        conversionsSum().as("conversions"),
                        creditedValueSum().as("credited"),
                        multiTouchSum().as("multi_touch"))
                .from(ROLLUP_CONVERSIONS_DAILY)
                .where(conversionCondition(query.range(), query.filter()))
                .groupBy(key)
                .fetch()
                .forEach(row -> {
                    MutableRow target = rows.computeIfAbsent(keyOf(row.get("key")), MutableRow::new);
                    target.conversions = row.get("conversions", BigDecimal.class);
                    target.creditedValue = row.get("credited", BigDecimal.class);
                    target.multiTouch = row.get("multi_touch", BigDecimal.class);
                });
    }

    @Override
    public FunnelView funnel(FunnelQuery query, Instant dataThrough) {
        TrafficSource.FunnelCounts counts =
                traffic.funnel(query.campaignId(), query.linkId(), query.from(), query.to());
        long converted = conversions.convertedClicks(
                query.campaignId(), query.linkId(), query.goalId(), query.from(), query.to());
        BigDecimal landedRate = ratio(BigDecimal.valueOf(counts.landed()), BigDecimal.valueOf(counts.clicks()));
        BigDecimal convertedRate = ratio(BigDecimal.valueOf(converted), BigDecimal.valueOf(counts.landed()));
        String entity = query.campaignId() != null ? "campaign" : "link";
        UUID entityId = query.campaignId() != null ? query.campaignId() : query.linkId();
        return new FunnelView(
                entity, entityId, query.goalId(), query.from(), query.to(), query.traffic().label(),
                counts.clicks(), counts.landed(), converted, landedRate, convertedRate, counts.botClicks(),
                dataThrough);
    }

    @Override
    public CohortView cohorts(CohortQuery query, Instant dataThrough) {
        ReportRange range = new ReportRange(query.from(), query.to());
        List<ConversionRollupSource.CohortCell> cells = conversions.cohorts(
                query.campaignId(), range.fromDay(), range.toDayExclusive(), query.periods());
        Map<LocalDate, List<ConversionRollupSource.CohortCell>> byWeek = new TreeMap<>();
        for (ConversionRollupSource.CohortCell cell : cells) {
            byWeek.computeIfAbsent(cell.cohortWeek(), ignored -> new ArrayList<>()).add(cell);
        }
        List<CohortView.Row> rows = new ArrayList<>(byWeek.size());
        byWeek.forEach((week, weekCells) -> {
            weekCells.sort(Comparator.comparingInt(ConversionRollupSource.CohortCell::week));
            long total = weekCells.stream().mapToLong(ConversionRollupSource.CohortCell::conversions).sum();
            BigDecimal value = weekCells.stream()
                    .map(ConversionRollupSource.CohortCell::value)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new CohortView.Row(
                    week.toString(),
                    weekCells.stream()
                            .map(cell -> new CohortView.Cell(cell.week(), cell.conversions(), cell.value()))
                            .toList(),
                    total,
                    value));
        });
        return new CohortView(query.campaignId(), "week", rows, dataThrough);
    }

    @Override
    public LinkStatsView linkStats(
            UUID linkId, String status, Long maxClicks, Instant now, Instant dataThrough) {
        long clicks = totalClicks(linkId, List.of("HUMAN"));
        long botClicks = totalClicks(linkId, List.of("BOT", "SUSPECT"));
        Field<LocalDate> day = DSL.field(
                "({0} at time zone 'UTC')::date", LocalDate.class, ROLLUP_TRAFFIC_HOURLY.BUCKET);
        List<LinkStatsView.DailyCount> daily = dsl.select(
                        day.as("day"),
                        DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.CLICKS).as("clicks"))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(ROLLUP_TRAFFIC_HOURLY.LINK_ID.eq(linkId))
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.ge(now.minus(30, java.time.temporal.ChronoUnit.DAYS)))
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(now))
                .and(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS.eq("HUMAN"))
                .groupBy(day)
                .orderBy(day.asc())
                .fetch(row -> new LinkStatsView.DailyCount(
                        row.get("day", LocalDate.class), row.get("clicks", Long.class)));
        return new LinkStatsView(linkId, status, maxClicks, clicks, daily, botClicks, dataThrough);
    }

    private long totalClicks(UUID linkId, List<String> classes) {
        Long total = dsl.select(DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.CLICKS))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(ROLLUP_TRAFFIC_HOURLY.LINK_ID.eq(linkId))
                .and(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS.in(classes))
                .fetchOne(0, Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public List<CampaignDayClicks> clicksByCampaignDay(ReportRange range, UUID campaignId) {
        Field<LocalDate> day = DSL.field(
                "({0} at time zone 'UTC')::date", LocalDate.class, ROLLUP_TRAFFIC_HOURLY.BUCKET);
        Condition where = ROLLUP_TRAFFIC_HOURLY.BUCKET.ge(range.from())
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(range.to()))
                .and(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS.eq("HUMAN"))
                .and(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID.isNotNull());
        if (campaignId != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID.eq(campaignId));
        }
        return dsl.select(
                        day.as("day"),
                        ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID,
                        DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.CLICKS).as("clicks"))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(where)
                .groupBy(day, ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID)
                .fetch(row -> new CampaignDayClicks(
                        row.get("day", LocalDate.class),
                        row.get(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID),
                        row.get("clicks", Long.class)));
    }

    @Override
    public List<CampaignDayConversions> conversionsByCampaignDay(ReportRange range, UUID campaignId) {
        Condition where = ROLLUP_CONVERSIONS_DAILY.DAY.ge(range.fromDay())
                .and(ROLLUP_CONVERSIONS_DAILY.DAY.lt(range.toDayExclusive()))
                .and(ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID.isNotNull());
        if (campaignId != null) {
            where = where.and(ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID.eq(campaignId));
        }
        return dsl.select(
                        ROLLUP_CONVERSIONS_DAILY.DAY,
                        ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID,
                        ROLLUP_CONVERSIONS_DAILY.CURRENCY,
                        conversionsSum().as("conversions"),
                        creditedValueSum().as("credited"),
                        multiTouchSum().as("multi_touch"))
                .from(ROLLUP_CONVERSIONS_DAILY)
                .where(where)
                .groupBy(
                        ROLLUP_CONVERSIONS_DAILY.DAY,
                        ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID,
                        ROLLUP_CONVERSIONS_DAILY.CURRENCY)
                .fetch(row -> new CampaignDayConversions(
                        row.get(ROLLUP_CONVERSIONS_DAILY.DAY),
                        row.get(ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID),
                        row.get(ROLLUP_CONVERSIONS_DAILY.CURRENCY),
                        row.get("conversions", BigDecimal.class),
                        row.get("credited", BigDecimal.class),
                        row.get("multi_touch", BigDecimal.class)));
    }

    private Field<OffsetDateTime> bucketField(Granularity granularity) {
        String unit = granularity == Granularity.HOUR ? "hour" : "day";
        return DSL.field(
                "date_trunc('" + unit + "', ({0} at time zone 'UTC')) at time zone 'UTC'",
                OffsetDateTime.class, ROLLUP_TRAFFIC_HOURLY.BUCKET);
    }

    private Condition trafficCondition(ReportRange range, ReportFilter filter, dev.ngb.attribution.insight.application.report.query.TrafficClassFilter trafficFilter) {
        Condition where = ROLLUP_TRAFFIC_HOURLY.BUCKET.ge(range.from())
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(range.to()))
                .and(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS.in(trafficFilter.classes()));
        if (filter.linkId() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.LINK_ID.eq(filter.linkId()));
        }
        if (filter.campaignId() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID.eq(filter.campaignId()));
        }
        if (filter.destinationId() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.DESTINATION_ID.eq(filter.destinationId()));
        }
        if (filter.entry() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.ENTRY.eq(filter.entry()));
        }
        if (filter.device() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.DEVICE_CLASS.eq(filter.device().strip().toUpperCase(Locale.ROOT)));
        }
        if (filter.country() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.COUNTRY.eq(filter.country().strip().toUpperCase(Locale.ROOT)));
        }
        return where;
    }

    private static Condition conversionCondition(ReportRange range, ReportFilter filter) {
        Condition where = ROLLUP_CONVERSIONS_DAILY.DAY.ge(range.fromDay())
                .and(ROLLUP_CONVERSIONS_DAILY.DAY.lt(range.toDayExclusive()));
        if (filter.linkId() != null) {
            where = where.and(ROLLUP_CONVERSIONS_DAILY.LINK_ID.eq(filter.linkId()));
        }
        if (filter.campaignId() != null) {
            where = where.and(ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID.eq(filter.campaignId()));
        }
        if (filter.goalId() != null) {
            where = where.and(ROLLUP_CONVERSIONS_DAILY.GOAL_ID.eq(filter.goalId()));
        }
        return where;
    }

    private long botClicks(ReportRange range, ReportFilter filter) {
        Condition where = ROLLUP_TRAFFIC_HOURLY.BUCKET.ge(range.from())
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(range.to()))
                .and(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS.in("BOT", "SUSPECT"));
        if (filter.linkId() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.LINK_ID.eq(filter.linkId()));
        }
        if (filter.campaignId() != null) {
            where = where.and(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID.eq(filter.campaignId()));
        }
        Long total = dsl.select(DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.CLICKS))
                .from(ROLLUP_TRAFFIC_HOURLY)
                .where(where)
                .fetchOne(0, Long.class);
        return total == null ? 0 : total;
    }

    private static Field<?> trafficDimension(Dimension dimension) {
        return switch (dimension) {
            case LINK -> ROLLUP_TRAFFIC_HOURLY.LINK_ID;
            case CAMPAIGN -> ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID;
            case ENTRY -> ROLLUP_TRAFFIC_HOURLY.ENTRY;
            case DEVICE -> ROLLUP_TRAFFIC_HOURLY.DEVICE_CLASS;
            case COUNTRY -> ROLLUP_TRAFFIC_HOURLY.COUNTRY;
            case DESTINATION -> ROLLUP_TRAFFIC_HOURLY.DESTINATION_ID;
            default -> throw new IllegalArgumentException("no traffic column for " + dimension);
        };
    }

    private static Field<?> conversionDimension(Dimension dimension) {
        return switch (dimension) {
            case LINK -> ROLLUP_CONVERSIONS_DAILY.LINK_ID;
            case CAMPAIGN -> ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID;
            case GOAL -> ROLLUP_CONVERSIONS_DAILY.GOAL_ID;
            default -> throw new IllegalArgumentException("no conversion column for " + dimension);
        };
    }

    private static Field<Long> clicksSum() {
        return DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.CLICKS);
    }

    private static Field<Long> pageViewsSum() {
        return DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.PAGE_VIEWS);
    }

    private static Field<Long> sessionsSum() {
        return DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.SESSIONS);
    }

    private static Field<Long> visitorDaysSum() {
        return DSL.field("coalesce(sum({0}), 0)", Long.class, ROLLUP_TRAFFIC_HOURLY.VISITOR_DAYS);
    }

    private static Field<BigDecimal> conversionsSum() {
        return DSL.field("coalesce(sum({0}), 0)", BigDecimal.class, ROLLUP_CONVERSIONS_DAILY.CONVERSIONS);
    }

    private static Field<BigDecimal> creditedValueSum() {
        return DSL.field("coalesce(sum({0}), 0)", BigDecimal.class, ROLLUP_CONVERSIONS_DAILY.CREDITED_VALUE);
    }

    private static Field<BigDecimal> multiTouchSum() {
        return DSL.field("coalesce(sum({0}), 0)", BigDecimal.class, ROLLUP_CONVERSIONS_DAILY.MULTI_TOUCH_CONVERSIONS);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String keyOf(Object value) {
        return value == null ? "(none)" : value.toString();
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return ratio(part, whole);
    }

    private static final class MutablePoint {

        private final Instant bucket;
        private Long clicks;
        private Long pageViews;
        private Long sessions;
        private Long visitorDays;
        private BigDecimal conversions;
        private BigDecimal creditedValue;
        private BigDecimal multiTouch;

        private MutablePoint(Instant bucket) {
            this.bucket = bucket;
        }

        private TimeSeriesView.Point toPoint(java.util.Set<Metric> metrics) {
            return new TimeSeriesView.Point(
                    bucket,
                    metrics.contains(Metric.CLICKS) ? clicks : null,
                    metrics.contains(Metric.PAGE_VIEWS) ? pageViews : null,
                    metrics.contains(Metric.SESSIONS) ? sessions : null,
                    metrics.contains(Metric.VISITOR_DAYS) ? visitorDays : null,
                    metrics.contains(Metric.CONVERSIONS) ? conversions : null,
                    metrics.contains(Metric.CREDITED_VALUE) ? creditedValue : null,
                    metrics.contains(Metric.CONVERSIONS) ? share(multiTouch, conversions) : null);
        }
    }

    private static final class MutableRow {

        private final String key;
        private Long clicks;
        private Long pageViews;
        private Long sessions;
        private Long visitorDays;
        private BigDecimal conversions;
        private BigDecimal creditedValue;
        private BigDecimal multiTouch;

        private MutableRow(String key) {
            this.key = key;
        }

        private BreakdownView.Row toRow(java.util.Set<Metric> metrics) {
            return new BreakdownView.Row(
                    key,
                    metrics.contains(Metric.CLICKS) ? clicks : null,
                    metrics.contains(Metric.PAGE_VIEWS) ? pageViews : null,
                    metrics.contains(Metric.SESSIONS) ? sessions : null,
                    metrics.contains(Metric.VISITOR_DAYS) ? visitorDays : null,
                    metrics.contains(Metric.CONVERSIONS) ? conversions : null,
                    metrics.contains(Metric.CREDITED_VALUE) ? creditedValue : null,
                    metrics.contains(Metric.CONVERSIONS) ? share(multiTouch, conversions) : null);
        }
    }
}

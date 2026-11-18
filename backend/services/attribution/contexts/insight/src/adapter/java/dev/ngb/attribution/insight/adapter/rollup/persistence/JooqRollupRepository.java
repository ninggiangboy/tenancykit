package dev.ngb.attribution.insight.adapter.rollup.persistence;

import static dev.ngb.attribution_jooq.Tables.ROLLUP_CONVERSIONS_DAILY;
import static dev.ngb.attribution_jooq.Tables.ROLLUP_CURSOR;
import static dev.ngb.attribution_jooq.Tables.ROLLUP_REFERRERS_DAILY;
import static dev.ngb.attribution_jooq.Tables.ROLLUP_TRAFFIC_HOURLY;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import dev.ngb.attribution.insight.application.rollup.port.RollupRepository;
import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;

/**
 * The rollup store. A window is deleted and rewritten in one tenant-scoped transaction, so a late event inside the
 * window is absorbed on the next run and an interrupted run never leaves two versions of one bucket.
 *
 * <p>The cursor is deployment-wide because retention drops shared partitions; it is the one table here without row-level
 * security.
 */
public class JooqRollupRepository implements RollupRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqRollupRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public void replaceTraffic(Instant from, Instant to, List<TrafficSource.TrafficBucket> buckets) {
        dsl.deleteFrom(ROLLUP_TRAFFIC_HOURLY)
                .where(ROLLUP_TRAFFIC_HOURLY.BUCKET.ge(from))
                .and(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(to))
                .execute();
        java.util.UUID tenant = tenantContext.requireTenant().value();
        for (TrafficSource.TrafficBucket bucket : buckets) {
            dsl.insertInto(ROLLUP_TRAFFIC_HOURLY)
                    .set(ROLLUP_TRAFFIC_HOURLY.TENANT_ID, tenant)
                    .set(ROLLUP_TRAFFIC_HOURLY.BUCKET, bucket.bucket())
                    .set(ROLLUP_TRAFFIC_HOURLY.LINK_ID, bucket.linkId())
                    .set(ROLLUP_TRAFFIC_HOURLY.CAMPAIGN_ID, bucket.campaignId())
                    .set(ROLLUP_TRAFFIC_HOURLY.DESTINATION_ID, bucket.destinationId())
                    .set(ROLLUP_TRAFFIC_HOURLY.DEVICE_CLASS, bucket.deviceClass())
                    .set(ROLLUP_TRAFFIC_HOURLY.COUNTRY, bucket.country())
                    .set(ROLLUP_TRAFFIC_HOURLY.ENTRY, bucket.entry())
                    .set(ROLLUP_TRAFFIC_HOURLY.TRAFFIC_CLASS, bucket.trafficClass())
                    .set(ROLLUP_TRAFFIC_HOURLY.CLICKS, bucket.clicks())
                    .set(ROLLUP_TRAFFIC_HOURLY.PAGE_VIEWS, bucket.pageViews())
                    .set(ROLLUP_TRAFFIC_HOURLY.SESSIONS, bucket.sessions())
                    .set(ROLLUP_TRAFFIC_HOURLY.VISITOR_DAYS, bucket.visitorDays())
                    .execute();
        }
    }

    @Override
    public void replaceReferrers(LocalDate fromDay, LocalDate toDayExclusive, List<TrafficSource.ReferrerCount> rows) {
        dsl.deleteFrom(ROLLUP_REFERRERS_DAILY)
                .where(ROLLUP_REFERRERS_DAILY.DAY.ge(fromDay))
                .and(ROLLUP_REFERRERS_DAILY.DAY.lt(toDayExclusive))
                .execute();
        java.util.UUID tenant = tenantContext.requireTenant().value();
        for (TrafficSource.ReferrerCount row : rows) {
            dsl.insertInto(ROLLUP_REFERRERS_DAILY)
                    .set(ROLLUP_REFERRERS_DAILY.TENANT_ID, tenant)
                    .set(ROLLUP_REFERRERS_DAILY.DAY, row.day())
                    .set(ROLLUP_REFERRERS_DAILY.LINK_ID, row.linkId())
                    .set(ROLLUP_REFERRERS_DAILY.REFERRER_DOMAIN, row.referrerDomain())
                    .set(ROLLUP_REFERRERS_DAILY.CLICKS, row.clicks())
                    .execute();
        }
    }

    @Override
    public void replaceConversions(
            LocalDate fromDay, LocalDate toDayExclusive, List<ConversionRollupSource.ConversionBucket> rows) {
        dsl.deleteFrom(ROLLUP_CONVERSIONS_DAILY)
                .where(ROLLUP_CONVERSIONS_DAILY.DAY.ge(fromDay))
                .and(ROLLUP_CONVERSIONS_DAILY.DAY.lt(toDayExclusive))
                .execute();
        java.util.UUID tenant = tenantContext.requireTenant().value();
        for (ConversionRollupSource.ConversionBucket row : rows) {
            dsl.insertInto(ROLLUP_CONVERSIONS_DAILY)
                    .set(ROLLUP_CONVERSIONS_DAILY.TENANT_ID, tenant)
                    .set(ROLLUP_CONVERSIONS_DAILY.DAY, row.day())
                    .set(ROLLUP_CONVERSIONS_DAILY.CAMPAIGN_ID, row.campaignId())
                    .set(ROLLUP_CONVERSIONS_DAILY.LINK_ID, row.linkId())
                    .set(ROLLUP_CONVERSIONS_DAILY.GOAL_ID, row.goalId())
                    .set(ROLLUP_CONVERSIONS_DAILY.MODEL, row.model())
                    .set(ROLLUP_CONVERSIONS_DAILY.CURRENCY, row.currency())
                    .set(ROLLUP_CONVERSIONS_DAILY.CONVERSIONS, row.conversions())
                    .set(ROLLUP_CONVERSIONS_DAILY.CREDITED_VALUE, row.creditedValue())
                    .set(ROLLUP_CONVERSIONS_DAILY.MULTI_TOUCH_CONVERSIONS, row.multiTouchConversions())
                    .execute();
        }
    }

    @Override
    public void advanceCursor(LocalDate rolledUpThrough, Instant dataThrough) {
        dsl.insertInto(ROLLUP_CURSOR)
                .set(ROLLUP_CURSOR.ID, (short) 1)
                .set(ROLLUP_CURSOR.ROLLED_UP_THROUGH, rolledUpThrough)
                .set(ROLLUP_CURSOR.DATA_THROUGH, dataThrough)
                .onConflict(ROLLUP_CURSOR.ID)
                .doUpdate()
                .set(ROLLUP_CURSOR.ROLLED_UP_THROUGH, rolledUpThrough)
                .set(ROLLUP_CURSOR.DATA_THROUGH, dataThrough)
                .execute();
    }

    @Override
    public void deleteOlderThan(LocalDate cutoff) {
        dsl.deleteFrom(ROLLUP_TRAFFIC_HOURLY)
                .where(ROLLUP_TRAFFIC_HOURLY.BUCKET.lt(cutoff.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .execute();
        dsl.deleteFrom(ROLLUP_REFERRERS_DAILY).where(ROLLUP_REFERRERS_DAILY.DAY.lt(cutoff)).execute();
        dsl.deleteFrom(ROLLUP_CONVERSIONS_DAILY)
                .where(ROLLUP_CONVERSIONS_DAILY.DAY.lt(cutoff))
                .execute();
    }
}

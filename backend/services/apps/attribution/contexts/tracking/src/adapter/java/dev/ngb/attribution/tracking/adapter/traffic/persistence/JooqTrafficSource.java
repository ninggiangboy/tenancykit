package dev.ngb.attribution.tracking.adapter.traffic.persistence;

import static dev.ngb.attribution_jooq.Tables.CLICK_EVENTS;
import static dev.ngb.attribution_jooq.Tables.PAGE_EVENTS;

import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The traffic aggregates behind the rollup, computed from the raw click and page-view tables in one pass each.
 *
 * <p>A page view or a session is attributed to the link of its click when the event carries a click id and counted under
 * a null link when it does not. That is deliberate: the traffic is real even when it cannot be tied to a link, and the
 * report shows the unlinked part under a null key rather than dropping it.
 *
 * <p>The referrer list is folded here, not in the database: at most fifty domains per link and day survive, the rest
 * become {@code other}. A report names that row so a reader knows the long tail was summarised.
 */
public class JooqTrafficSource implements TrafficSource {

    private static final int REFERRERS_PER_LINK_DAY = 50;
    private static final String OTHER = "other";
    private static final String DIRECT = "(direct)";
    private static final String UNKNOWN_DEVICE = "UNKNOWN";

    private final DSLContext dsl;

    public JooqTrafficSource(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TrafficBucket> buckets(Instant from, Instant to) {
        Map<TrafficKey, MutableBucket> merged = new LinkedHashMap<>();
        clicks(from, to, merged);
        pageViews(from, to, merged);
        return merged.values().stream().map(MutableBucket::toBucket).toList();
    }

    private void clicks(Instant from, Instant to, Map<TrafficKey, MutableBucket> merged) {
        Field<OffsetDateTime> bucket = hourBucket(CLICK_EVENTS.OCCURRED_AT);
        dsl.select(
                        bucket.as("bucket"),
                        CLICK_EVENTS.LINK_ID,
                        CLICK_EVENTS.CAMPAIGN_ID,
                        CLICK_EVENTS.DESTINATION_ID,
                        CLICK_EVENTS.DEVICE_CLASS,
                        CLICK_EVENTS.COUNTRY,
                        CLICK_EVENTS.ENTRY,
                        CLICK_EVENTS.TRAFFIC_CLASS,
                        DSL.count().as("clicks"),
                        DSL.countDistinct(CLICK_EVENTS.VISITOR_HASH).as("visitors"))
                .from(CLICK_EVENTS)
                .where(CLICK_EVENTS.OCCURRED_AT.ge(from).and(CLICK_EVENTS.OCCURRED_AT.lt(to)))
                .groupBy(
                        bucket,
                        CLICK_EVENTS.LINK_ID,
                        CLICK_EVENTS.CAMPAIGN_ID,
                        CLICK_EVENTS.DESTINATION_ID,
                        CLICK_EVENTS.DEVICE_CLASS,
                        CLICK_EVENTS.COUNTRY,
                        CLICK_EVENTS.ENTRY,
                        CLICK_EVENTS.TRAFFIC_CLASS)
                .fetch()
                .forEach(row -> {
                    TrafficKey key = key(row, row.get("bucket", OffsetDateTime.class),
                            row.get(CLICK_EVENTS.TRAFFIC_CLASS));
                    MutableBucket target = merged.computeIfAbsent(key, MutableBucket::new);
                    target.clicks += row.get("clicks", Long.class);
                    target.visitorDays += row.get("visitors", Long.class);
                });
    }

    private void pageViews(Instant from, Instant to, Map<TrafficKey, MutableBucket> merged) {
        Field<OffsetDateTime> bucket = hourBucket(PAGE_EVENTS.OCCURRED_AT);
        dsl.select(
                        bucket.as("bucket"),
                        CLICK_EVENTS.LINK_ID,
                        CLICK_EVENTS.CAMPAIGN_ID,
                        CLICK_EVENTS.DESTINATION_ID,
                        CLICK_EVENTS.DEVICE_CLASS,
                        CLICK_EVENTS.COUNTRY,
                        CLICK_EVENTS.ENTRY,
                        PAGE_EVENTS.TRAFFIC_CLASS,
                        DSL.count().as("page_views"),
                        DSL.countDistinct(PAGE_EVENTS.SESSION_ID).as("sessions"))
                .from(PAGE_EVENTS)
                .leftJoin(CLICK_EVENTS)
                .on(CLICK_EVENTS.TENANT_ID.eq(PAGE_EVENTS.TENANT_ID)
                        .and(CLICK_EVENTS.CLICK_ID.eq(PAGE_EVENTS.CLICK_ID)))
                .where(PAGE_EVENTS.OCCURRED_AT.ge(from).and(PAGE_EVENTS.OCCURRED_AT.lt(to)))
                .groupBy(
                        bucket,
                        CLICK_EVENTS.LINK_ID,
                        CLICK_EVENTS.CAMPAIGN_ID,
                        CLICK_EVENTS.DESTINATION_ID,
                        CLICK_EVENTS.DEVICE_CLASS,
                        CLICK_EVENTS.COUNTRY,
                        CLICK_EVENTS.ENTRY,
                        PAGE_EVENTS.TRAFFIC_CLASS)
                .fetch()
                .forEach(row -> {
                    TrafficKey key = key(row, row.get("bucket", OffsetDateTime.class),
                            row.get(PAGE_EVENTS.TRAFFIC_CLASS));
                    MutableBucket target = merged.computeIfAbsent(key, MutableBucket::new);
                    target.pageViews += row.get("page_views", Long.class);
                    target.sessions += row.get("sessions", Long.class);
                });
    }

    private static TrafficKey key(Record row, OffsetDateTime bucket, String trafficClass) {
        String device = row.get(CLICK_EVENTS.DEVICE_CLASS);
        return new TrafficKey(
                bucket.toInstant(),
                row.get(CLICK_EVENTS.LINK_ID),
                row.get(CLICK_EVENTS.CAMPAIGN_ID),
                row.get(CLICK_EVENTS.DESTINATION_ID),
                device == null || device.isBlank() ? UNKNOWN_DEVICE : device,
                row.get(CLICK_EVENTS.COUNTRY),
                row.get(CLICK_EVENTS.ENTRY),
                trafficClass);
    }

    private static Field<OffsetDateTime> hourBucket(Field<Instant> occurredAt) {
        // Force UTC rather than the session's zone: every rollup bucket is UTC, whatever a deployment configures.
        return DSL.field(
                "date_trunc('hour', ({0} at time zone 'UTC')) at time zone 'UTC'", OffsetDateTime.class, occurredAt);
    }

    @Override
    public List<ReferrerCount> referrers(LocalDate fromDay, LocalDate toDayExclusive) {
        Field<LocalDate> day = DSL.field(
                "({0} at time zone 'UTC')::date", LocalDate.class, CLICK_EVENTS.OCCURRED_AT);
        List<RefRow> rows = dsl.select(
                        day.as("day"),
                        CLICK_EVENTS.LINK_ID,
                        DSL.coalesce(CLICK_EVENTS.REFERRER_DOMAIN, DSL.inline(DIRECT)).as("referrer"),
                        DSL.count().as("clicks"))
                .from(CLICK_EVENTS)
                .where(CLICK_EVENTS.OCCURRED_AT.ge(startOf(fromDay)).and(CLICK_EVENTS.OCCURRED_AT.lt(startOf(toDayExclusive))))
                .and(CLICK_EVENTS.TRAFFIC_CLASS.eq("HUMAN"))
                .groupBy(day, CLICK_EVENTS.LINK_ID, CLICK_EVENTS.REFERRER_DOMAIN)
                .fetch(row -> new RefRow(
                        row.get("day", LocalDate.class),
                        row.get(CLICK_EVENTS.LINK_ID),
                        row.get("referrer", String.class),
                        row.get("clicks", Long.class)));

        Map<LinkDay, List<RefRow>> byLinkDay = new LinkedHashMap<>();
        for (RefRow row : rows) {
            byLinkDay.computeIfAbsent(new LinkDay(row.day(), row.linkId()), ignored -> new ArrayList<>()).add(row);
        }
        Map<ReferrerKey, Long> folded = new LinkedHashMap<>();
        byLinkDay.forEach((group, groupRows) -> {
            groupRows.sort(Comparator.comparingLong(RefRow::clicks).reversed()
                    .thenComparing(RefRow::domain));
            long other = 0;
            for (int index = 0; index < groupRows.size(); index++) {
                RefRow row = groupRows.get(index);
                if (index < REFERRERS_PER_LINK_DAY) {
                    folded.merge(new ReferrerKey(group.day(), group.linkId(), row.domain()), row.clicks(), Long::sum);
                } else {
                    other += row.clicks();
                }
            }
            if (other > 0) {
                // Merge rather than add: a real domain named "other" would otherwise make the unique key collide.
                folded.merge(new ReferrerKey(group.day(), group.linkId(), OTHER), other, Long::sum);
            }
        });
        return folded.entrySet().stream()
                .map(entry -> new ReferrerCount(
                        entry.getKey().day(), entry.getKey().linkId(), entry.getKey().domain(), entry.getValue()))
                .toList();
    }

    @Override
    public FunnelCounts funnel(UUID campaignId, UUID linkId, Instant from, Instant to) {
        Condition window = CLICK_EVENTS.OCCURRED_AT.ge(from).and(CLICK_EVENTS.OCCURRED_AT.lt(to));
        if (campaignId != null) {
            window = window.and(CLICK_EVENTS.CAMPAIGN_ID.eq(campaignId));
        }
        if (linkId != null) {
            window = window.and(CLICK_EVENTS.LINK_ID.eq(linkId));
        }
        Condition narrowed = window.and(CLICK_EVENTS.TRAFFIC_CLASS.eq("HUMAN"));
        Condition landed = DSL.exists(dsl.selectOne()
                .from(PAGE_EVENTS)
                .where(PAGE_EVENTS.TENANT_ID.eq(CLICK_EVENTS.TENANT_ID))
                .and(PAGE_EVENTS.CLICK_ID.eq(CLICK_EVENTS.CLICK_ID))
                .and(PAGE_EVENTS.OCCURRED_AT.ge(CLICK_EVENTS.OCCURRED_AT))
                .and(PAGE_EVENTS.OCCURRED_AT.lt(to)));
        long clicks = dsl.fetchCount(CLICK_EVENTS, narrowed);
        long landedClicks = dsl.fetchCount(CLICK_EVENTS, narrowed.and(landed));
        long botClicks = dsl.fetchCount(
                CLICK_EVENTS, window.and(CLICK_EVENTS.TRAFFIC_CLASS.in("BOT", "SUSPECT")));
        return new FunnelCounts(clicks, landedClicks, botClicks);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private record TrafficKey(
            Instant bucket,
            UUID linkId,
            UUID campaignId,
            UUID destinationId,
            String deviceClass,
            String country,
            String entry,
            String trafficClass) {}

    private record LinkDay(LocalDate day, UUID linkId) {}

    private record ReferrerKey(LocalDate day, UUID linkId, String domain) {}

    private record RefRow(LocalDate day, UUID linkId, String domain, long clicks) {}

    private static final class MutableBucket {

        private final TrafficKey key;
        private long clicks;
        private long pageViews;
        private long sessions;
        private long visitorDays;

        private MutableBucket(TrafficKey key) {
            this.key = key;
        }

        private TrafficBucket toBucket() {
            return new TrafficBucket(
                    key.bucket(),
                    key.linkId(),
                    key.campaignId(),
                    key.destinationId(),
                    key.deviceClass(),
                    key.country(),
                    key.entry(),
                    key.trafficClass(),
                    clicks,
                    pageViews,
                    sessions,
                    visitorDays);
        }
    }
}

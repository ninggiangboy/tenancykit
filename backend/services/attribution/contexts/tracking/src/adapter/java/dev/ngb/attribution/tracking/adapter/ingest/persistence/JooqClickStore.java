package dev.ngb.attribution.tracking.adapter.ingest.persistence;

import static dev.ngb.attribution_jooq.Tables.CLICK_EVENTS;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.attribution.tracking.application.ingest.port.ClickStore;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Query;

/**
 * The click store: one tenant's batch in one statement, idempotent on the event id.
 *
 * <p>The table is partitioned by day and the caller has already grouped by tenant, so a batch inserts into one or two
 * partitions and never across a tenant boundary. {@code on conflict (event_id, occurred_at) do nothing} is what makes a
 * redelivered batch harmless: the broker's at-least-once delivery cannot double a click.
 */
public class JooqClickStore implements ClickStore {

    private final DSLContext dsl;

    public JooqClickStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int insertAll(List<ClickEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<Query> inserts = events.stream().map(this::insert).toList();
        int[] counts = dsl.batch(inserts).execute();
        int stored = 0;
        for (int count : counts) {
            // jOOQ reports SUCCESS_NO_INFO (-2) when the driver does not count; either way a row was attempted, and the
            // conflict clause is what decides whether it was written.
            if (count > 0 || count == java.sql.Statement.SUCCESS_NO_INFO) {
                stored += count > 0 ? count : 1;
            }
        }
        return stored;
    }

    private Query insert(ClickEvent event) {
        return dsl.insertInto(CLICK_EVENTS)
                .set(CLICK_EVENTS.EVENT_ID, event.eventId())
                .set(CLICK_EVENTS.OCCURRED_AT, event.occurredAt())
                .set(CLICK_EVENTS.TENANT_ID, event.tenantId().value())
                .set(CLICK_EVENTS.CLICK_ID, event.clickId())
                .set(CLICK_EVENTS.LINK_ID, event.linkId())
                .set(CLICK_EVENTS.DESTINATION_ID, event.destinationId())
                .set(CLICK_EVENTS.CAMPAIGN_ID, event.campaignId())
                .set(CLICK_EVENTS.DEVICE_CLASS, event.deviceClass().name())
                .set(CLICK_EVENTS.BROWSER_FAMILY, event.browserFamily() == null ? "Unknown" : event.browserFamily())
                .set(CLICK_EVENTS.COUNTRY, event.country())
                .set(CLICK_EVENTS.REFERRER_DOMAIN, event.referrerDomain())
                .set(CLICK_EVENTS.ENTRY, event.entry())
                .set(CLICK_EVENTS.TRAFFIC_CLASS, event.trafficClass().name())
                .set(CLICK_EVENTS.VISITOR_HASH, event.visitorHash())
                .onConflict(CLICK_EVENTS.EVENT_ID, CLICK_EVENTS.OCCURRED_AT)
                .doNothing();
    }
}

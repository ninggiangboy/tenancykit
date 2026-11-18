package dev.ngb.attribution.tracking.adapter.collect.persistence;

import static dev.ngb.attribution_jooq.Tables.PAGE_EVENTS;
import static dev.ngb.attribution_jooq.Tables.SESSIONS;
import static dev.ngb.attribution_jooq.Tables.VISITOR_CLICKS;

import dev.ngb.attribution.tracking.api.collect.PageViewEvent;
import dev.ngb.attribution.tracking.application.collect.port.PageViewStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Query;

/**
 * Stores a tenant's collected page views, their sessions and the bindings from a visitor to the clicks that brought them.
 *
 * <p>A page view inserts idempotently on its event id, so a batched retry writes nothing twice. A session upserts by
 * `(tenant, session_id)`, extending its last activity and counting the page; a gap longer than the session timeout is the
 * client's cue to mint a new session id, so the server does not need a clock-based split. A binding is inserted with
 * `on conflict do nothing`, because one visitor may meet the same click more than once.
 */
public class JooqPageViewStore implements PageViewStore {

    private final DSLContext dsl;

    public JooqPageViewStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int store(List<PageViewEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int stored = 0;
        for (PageViewEvent event : events) {
            if (storePageView(event)) {
                stored++;
            }
            if (event.visitorId() != null && event.sessionId() != null) {
                upsertSession(event);
            }
            if (event.visitorId() != null && event.clickId() != null) {
                bind(event);
            }
        }
        return stored;
    }

    private boolean storePageView(PageViewEvent event) {
        Query insert = dsl.insertInto(PAGE_EVENTS)
                .set(PAGE_EVENTS.EVENT_ID, event.eventId())
                .set(PAGE_EVENTS.OCCURRED_AT, event.occurredAt())
                .set(PAGE_EVENTS.TENANT_ID, event.tenantId().value())
                .set(PAGE_EVENTS.CLICK_ID, event.clickId())
                .set(PAGE_EVENTS.SESSION_ID, event.sessionId())
                .set(PAGE_EVENTS.VISITOR_ID, event.visitorId())
                .set(PAGE_EVENTS.PATH, event.path())
                .set(PAGE_EVENTS.TRAFFIC_CLASS, event.bot() ? "BOT" : "HUMAN")
                .onConflict(PAGE_EVENTS.EVENT_ID, PAGE_EVENTS.OCCURRED_AT)
                .doNothing();
        return insert.execute() > 0;
    }

    private void upsertSession(PageViewEvent event) {
        // The click that started the session is recorded once; a later page view must not overwrite it with its own (or
        // with none), because the session's first click is what tells a report where the visit began.
        dsl.insertInto(SESSIONS)
                .set(SESSIONS.TENANT_ID, event.tenantId().value())
                .set(SESSIONS.SESSION_ID, event.sessionId())
                .set(SESSIONS.VISITOR_ID, event.visitorId())
                .set(SESSIONS.CLICK_ID, event.clickId())
                .set(SESSIONS.STARTED_AT, event.occurredAt())
                .set(SESSIONS.LAST_ACTIVITY_AT, event.occurredAt())
                .set(SESSIONS.PAGE_COUNT, 1)
                .onConflict(SESSIONS.TENANT_ID, SESSIONS.SESSION_ID)
                .doUpdate()
                .set(SESSIONS.LAST_ACTIVITY_AT, event.occurredAt())
                .set(SESSIONS.PAGE_COUNT, SESSIONS.PAGE_COUNT.plus(1))
                .execute();
    }

    private void bind(PageViewEvent event) {
        Instant boundAt = event.occurredAt().truncatedTo(ChronoUnit.SECONDS);
        dsl.insertInto(VISITOR_CLICKS)
                .set(VISITOR_CLICKS.TENANT_ID, event.tenantId().value())
                .set(VISITOR_CLICKS.VISITOR_ID, event.visitorId())
                .set(VISITOR_CLICKS.CLICK_ID, event.clickId())
                .set(VISITOR_CLICKS.BOUND_AT, boundAt)
                .onConflict(VISITOR_CLICKS.TENANT_ID, VISITOR_CLICKS.VISITOR_ID, VISITOR_CLICKS.CLICK_ID)
                .doNothing()
                .execute();
    }
}

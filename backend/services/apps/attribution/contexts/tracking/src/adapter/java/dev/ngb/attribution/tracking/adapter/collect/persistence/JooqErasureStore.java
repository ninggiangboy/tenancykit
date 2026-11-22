package dev.ngb.attribution.tracking.adapter.collect.persistence;

import static dev.ngb.attribution_jooq.Tables.PAGE_EVENTS;
import static dev.ngb.attribution_jooq.Tables.SESSIONS;
import static dev.ngb.attribution_jooq.Tables.VISITOR_CLICKS;

import dev.ngb.attribution.tracking.application.collect.port.ErasureStore;
import org.jooq.DSLContext;

/**
 * Erasure against the collection tables.
 *
 * <p>The order matters. The bindings are read first, because they are what names the clicks that belong only to this
 * visitor; a click another visitor also met is not this person's to delete. The page views and sessions go next, and the
 * clicks that no binding names any more last. Everything runs in the caller's tenant-scoped transaction, so a query can
 * only see this tenant's rows.
 */
public class JooqErasureStore implements ErasureStore {

    private final DSLContext dsl;

    public JooqErasureStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Removed erase(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            return new Removed(0, 0, 0, 0);
        }
        java.util.List<java.util.UUID> boundClicks = dsl.select(VISITOR_CLICKS.CLICK_ID)
                .from(VISITOR_CLICKS)
                .where(VISITOR_CLICKS.VISITOR_ID.eq(visitorId))
                .fetch(VISITOR_CLICKS.CLICK_ID);

        int pageViews = dsl.deleteFrom(PAGE_EVENTS).where(PAGE_EVENTS.VISITOR_ID.eq(visitorId)).execute();
        int sessions = dsl.deleteFrom(SESSIONS).where(SESSIONS.VISITOR_ID.eq(visitorId)).execute();
        int bindings = dsl.deleteFrom(VISITOR_CLICKS).where(VISITOR_CLICKS.VISITOR_ID.eq(visitorId)).execute();

        int clicks = 0;
        for (java.util.UUID clickId : boundClicks) {
            // Delete the click only when no other visitor is still bound to it, so one person's erasure does not remove
            // another's attribution.
            boolean stillBound = dsl.fetchExists(VISITOR_CLICKS, VISITOR_CLICKS.CLICK_ID.eq(clickId));
            if (!stillBound) {
                clicks += dsl.deleteFrom(dev.ngb.attribution_jooq.Tables.CLICK_EVENTS)
                        .where(dev.ngb.attribution_jooq.Tables.CLICK_EVENTS.CLICK_ID.eq(clickId))
                        .execute();
            }
        }
        return new Removed(pageViews, sessions, bindings, clicks);
    }
}

package dev.ngb.attribution.tracking.application.collect.port;

/**
 * Removes one visitor's collected rows.
 *
 * <p>Erasure deletes the page views, sessions and bindings a visitor id names, and the clicks bound only to that
 * visitor. Rollups hold aggregates only and are untouched, so a report's totals stay and the person does not. A
 * conversion keeps its amount and loses its visitor and click, which a later phase does; this port removes what the
 * collection owns.
 */
public interface ErasureStore {

    /**
     * @param visitorId the consented visitor id whose rows are removed
     * @return counts of what was removed, so the caller can show the Tenant
     */
    Removed erase(String visitorId);

    /**
     * What an erasure removed.
     *
     * @param pageViews rows deleted from the page-view store
     * @param sessions session rows deleted
     * @param bindings visitor-to-click bindings deleted
     * @param clicks click rows deleted because this visitor was their only binding
     */
    record Removed(int pageViews, int sessions, int bindings, int clicks) {}
}

package dev.ngb.attribution.tracking.application.collect.port;

import dev.ngb.attribution.tracking.api.collect.PageViewEvent;
import java.util.List;

/** The write side of the collection store: a validated batch of page views, its sessions and its bindings. */
public interface PageViewStore {

    /**
     * Store one tenant's validated page views and everything that follows from them.
     *
     * <p>The caller has already grouped by tenant and dropped any identifier the visitor did not consent to, so this
     * writes exactly what the row may say. A redelivered batch writes nothing twice, because the page view's insert is
     * idempotent on the event id; a binding and a session upsert are naturally idempotent.
     *
     * @return how many page views were actually written
     */
    int store(List<PageViewEvent> events);
}

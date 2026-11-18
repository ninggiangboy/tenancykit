package dev.ngb.attribution.tracking.application.ingest.port;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;

/** The write side of the click store: one tenant's batch, inserted idempotently. */
public interface ClickStore {

    /**
     * Insert one tenant's clicks, ignoring any this store already holds.
     *
     * <p>The caller groups a broker batch by tenant and calls this once per group, because row-level security means a
     * transaction sees exactly one tenant. The insert is idempotent on the event id, so a redelivered batch writes
     * nothing twice.
     *
     * @return how many rows were actually written, which is fewer than {@code events} when a batch was a redelivery
     */
    int insertAll(java.util.List<ClickEvent> events);
}

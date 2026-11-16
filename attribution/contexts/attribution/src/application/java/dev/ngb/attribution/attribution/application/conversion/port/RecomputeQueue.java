package dev.ngb.attribution.attribution.application.conversion.port;

import java.util.UUID;

/**
 * The campaigns waiting for a recompute, and each one's progress.
 *
 * <p>A model or window change on a campaign enqueues a run; this port is what the job reads to find the next one and
 * what it updates as a run advances. The table behind it has a partial unique index, so a second request for the same
 * campaign joins the pending run instead of starting a parallel pass.
 */
public interface RecomputeQueue {

    /** One campaign's run, with the cursor a previous pass reached. */
    record Run(UUID campaignId, UUID cursor) {}

    /**
     * The next run to advance in the current tenant, or null when none is waiting.
     *
     * <p>A pending run is one no worker holds; a running one is resumed only when its worker's lease has lapsed, so an
     * interrupted run still finishes.
     */
    Run next();

    /** Record that a run advanced to a cursor and is still going. */
    void advance(Run run, UUID cursor);

    /** Mark a run done. */
    void finish(Run run);

    /** Record a failure, leaving the cursor so the next pass resumes from it. */
    void fail(Run run, RuntimeException failure);

    /** Enqueue a campaign's recompute, joining a pending one rather than starting a second. */
    void enqueue(UUID tenantId, UUID campaignId, String reason);

    /** Enqueue for the tenant bound to the current request, which is what a campaign edit is running inside. */
    void enqueueCurrentTenant(UUID campaignId, String reason);
}

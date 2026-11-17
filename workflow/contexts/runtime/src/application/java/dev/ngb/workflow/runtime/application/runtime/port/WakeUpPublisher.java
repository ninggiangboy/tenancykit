package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.core.id.TenantId;

/**
 * Tells the workers that a tenant has runnable work. The message only shortens latency: a lost one costs the sweeper's
 * interval, never correctness, because Postgres holds the truth.
 */
public interface WakeUpPublisher {

    void wake(TenantId tenantId);
}

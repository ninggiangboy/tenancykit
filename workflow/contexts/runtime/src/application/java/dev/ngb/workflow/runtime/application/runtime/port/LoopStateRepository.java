package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where one for-each keeps its progress: the list, how many iterations started, how many came back, and what each one
 * produced. A body token's arrival is one row lock, so concurrent arrivals never lose a count or start one item twice.
 */
public interface LoopStateRepository {

    /** One iteration to start: its index and the item the body reads. */
    record Iteration(int index, Object item) {}

    /** What the first arrival started. */
    record Start(int total, List<Iteration> toStart) {}

    /** What one body arrival concluded: the iterations to start, and whether the loop left its body. */
    record Advance(List<Iteration> toStart, boolean complete, Map<String, Object> outputs, Map<String, Object> errors) {}

    /** Creates the loop's state and starts the first batch the concurrency allows. */
    Start start(TenantId tenantId, UUID instanceId, String nodeId, List<Object> items, int concurrency, Instant now);

    /**
     * Records one finished iteration and starts the replacement that keeps the concurrency full.
     *
     * @param startNext false when the iteration failed and the instance is about to stop, so nothing new begins
     */
    Advance advance(
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            int iteration,
            Object output,
            Object error,
            boolean startNext,
            Instant now);
}

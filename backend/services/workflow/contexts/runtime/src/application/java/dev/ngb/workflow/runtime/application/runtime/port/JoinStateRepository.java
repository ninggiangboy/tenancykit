package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Where the branches of one join are counted and their outputs collected. */
public interface JoinStateRepository {

    /**
     * Counts one arrival in a single conditional update, then merges the branch's output under its name.
     *
     * @return how many branches have arrived and, once all have, their outputs by branch name
     */
    Arrival arrive(
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            int expected,
            String branchKey,
            Object contribution,
            Instant now);

    record Arrival(int arrived, int expected, Map<String, Object> outputs) {
        public boolean complete() {
            return arrived >= expected;
        }
    }
}

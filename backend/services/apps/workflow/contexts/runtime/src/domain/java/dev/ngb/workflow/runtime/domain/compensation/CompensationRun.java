package dev.ngb.workflow.runtime.domain.compensation;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

public record CompensationRun(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        UUID stepRunId,
        String nodeId,
        String status,
        int attempts,
        String failure,
        Instant startedAt,
        Instant finishedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public static CompensationRun started(
            UUID id, TenantId tenantId, UUID instanceId, UUID stepRunId, String nodeId, Instant startedAt) {
        return new CompensationRun(
                id, tenantId, instanceId, stepRunId, nodeId, RUNNING, 1, null, startedAt, null);
    }

    public CompensationRun attempted() {
        return new CompensationRun(
                id, tenantId, instanceId, stepRunId, nodeId, RUNNING, attempts + 1, null, startedAt, null);
    }

    public CompensationRun finished(String status, String failure, Instant finishedAt) {
        return new CompensationRun(
                id, tenantId, instanceId, stepRunId, nodeId, status, attempts, failure, startedAt, finishedAt);
    }
}

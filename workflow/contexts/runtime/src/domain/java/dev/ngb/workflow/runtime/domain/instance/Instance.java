package dev.ngb.workflow.runtime.domain.instance;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One run of a published version: the input it started with, the variables set so far, and whether it is still moving.
 *
 * <p>An instance pins the version id it started with. Its {@code version} is the optimistic lock every write compares,
 * so a worker that read an older state can never overwrite a newer one.
 */
public record Instance(
        UUID id,
        TenantId tenantId,
        UUID definitionId,
        UUID versionId,
        int versionNumber,
        InstanceStatus status,
        Object input,
        Map<String, Object> variables,
        String correlationKey,
        String startedBy,
        Instant startedAt,
        Instant finishedAt,
        String idempotencyKey,
        String requestHash,
        long version) {

    public Instance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public static Instance start(
            UUID id,
            TenantId tenantId,
            UUID definitionId,
            UUID versionId,
            int versionNumber,
            Object input,
            String correlationKey,
            String startedBy,
            String idempotencyKey,
            String requestHash,
            Instant now) {
        return new Instance(
                id, tenantId, definitionId, versionId, versionNumber, InstanceStatus.RUNNING, input, Map.of(),
                correlationKey, startedBy, now, null, idempotencyKey, requestHash, 1);
    }

    public boolean terminal() {
        return status.terminal();
    }

    /** The same instance with the variables it carries after a step. */
    public Instance withVariables(Map<String, Object> values) {
        return new Instance(
                id, tenantId, definitionId, versionId, versionNumber, status, input, values, correlationKey,
                startedBy, startedAt, finishedAt, idempotencyKey, requestHash, version);
    }

    /** The same instance at a terminal status. */
    public Instance finished(InstanceStatus terminal, Instant now) {
        return new Instance(
                id, tenantId, definitionId, versionId, versionNumber, terminal, input, variables, correlationKey,
                startedBy, startedAt, now, idempotencyKey, requestHash, version);
    }
}

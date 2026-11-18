package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

public record StepRun(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        UUID tokenId,
        String nodeId,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        String outcome,
        String errorClass,
        String failure,
        Object request,
        Object response,
        String idempotencyKey) {}

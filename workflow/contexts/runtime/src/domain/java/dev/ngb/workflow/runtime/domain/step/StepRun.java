package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * One finished step: what was tried, what came back and the key a target may use to recognise a repeated send.
 *
 * <p>Append-only. A retry of the same token and attempt cannot insert a second row, which is what makes "one completion
 * per step" a database fact rather than a promise.
 */
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
        Object request,
        Object response,
        String idempotencyKey) {}

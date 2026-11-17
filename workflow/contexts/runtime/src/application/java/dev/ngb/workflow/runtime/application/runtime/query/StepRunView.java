package dev.ngb.workflow.runtime.application.runtime.query;

import java.time.Instant;
import java.util.UUID;

public record StepRunView(
        UUID id,
        String nodeId,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        String outcome,
        String errorClass,
        String failure,
        Object response,
        String idempotencyKey) {}

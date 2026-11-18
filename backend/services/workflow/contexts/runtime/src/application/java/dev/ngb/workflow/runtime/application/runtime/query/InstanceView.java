package dev.ngb.workflow.runtime.application.runtime.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One instance as the history API shows it: state, pinned version and deadlines, never the input. */
public record InstanceView(
        UUID id,
        String status,
        UUID definitionId,
        String definitionCode,
        int version,
        String correlationKey,
        String failureReason,
        Instant startedAt,
        Instant finishedAt,
        long liveTokens,
        long steps,
        long rowVersion) {}

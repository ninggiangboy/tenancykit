package dev.ngb.workflow.runtime.domain.timer;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One deadline a token is parked on.
 *
 * <p>A timer is a row, not a memory: a worker that restarts, a process that dies and a clock that jumps all leave the
 * deadline readable. Firing it twice changes nothing because the token's WAITING -> READY update is conditional and the
 * timer row is marked fired conditionally.
 */
public record Timer(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        UUID tokenId,
        TimerKind kind,
        Instant dueAt,
        Instant firedAt,
        Instant createdAt) {

    public Timer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Timer due(
            UUID id, TenantId tenantId, UUID instanceId, UUID tokenId, TimerKind kind, Instant dueAt, Instant now) {
        return new Timer(id, tenantId, instanceId, tokenId, kind, dueAt, null, now);
    }

    public boolean fired() {
        return firedAt != null;
    }
}

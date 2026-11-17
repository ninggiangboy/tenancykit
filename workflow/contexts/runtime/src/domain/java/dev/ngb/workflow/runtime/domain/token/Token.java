package dev.ngb.workflow.runtime.domain.token;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One walker of a graph: the node it is at, and the ownership of whoever is running it.
 *
 * <p>A worker claims a READY token by setting it RUNNING with a lease. The lease is ownership by deadline: a worker that
 * dies lets the deadline pass and the sweeper makes the token READY again. A completion is a conditional update on the
 * status and the owner, so a worker that lost its lease cannot write a second completion.
 *
 * @param iteration the for-each iteration this token belongs to, zero outside one
 * @param attempt the number of times this token has been claimed; the step history uses it as its key
 * @param resumeBranch which way a parked token was resumed: {@code delay}, {@code event} or {@code timeout}
 * @param resumePayload the event data a wait resumed with, or null
 * @param payload the item of a for-each iteration, or null outside one
 * @param loopNodeId the for-each node this token walks for, or null outside one
 */
public record Token(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        String nodeId,
        TokenStatus status,
        UUID parentTokenId,
        String branchKey,
        int iteration,
        int attempt,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        String resumeBranch,
        Object resumePayload,
        Object payload,
        String loopNodeId,
        Instant createdAt,
        Instant updatedAt) {

    public Token {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Token ready(
            UUID id, TenantId tenantId, UUID instanceId, String nodeId, UUID parentTokenId, int iteration, Instant now) {
        return ready(id, tenantId, instanceId, nodeId, parentTokenId, null, iteration, now);
    }

    /** A ready token that carries the branch it belongs to, so a join can collect its output by name. */
    public static Token ready(
            UUID id,
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            UUID parentTokenId,
            String branchKey,
            int iteration,
            Instant now) {
        return ready(id, tenantId, instanceId, nodeId, parentTokenId, branchKey, iteration, null, null, now);
    }

    /** A ready token that carries the branch, the for-each iteration, the item and the loop that started it. */
    public static Token ready(
            UUID id,
            TenantId tenantId,
            UUID instanceId,
            String nodeId,
            UUID parentTokenId,
            String branchKey,
            int iteration,
            Object payload,
            String loopNodeId,
            Instant now) {
        return new Token(
                id, tenantId, instanceId, nodeId, TokenStatus.READY, parentTokenId, branchKey, iteration, 0, now, null,
                null, null, null, payload, loopNodeId, now, now);
    }
}

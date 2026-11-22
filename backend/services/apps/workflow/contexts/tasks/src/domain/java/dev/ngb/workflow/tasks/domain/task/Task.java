package dev.ngb.workflow.tasks.domain.task;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One human task: who has to decide, by when, what they may decide and what they decided.
 *
 * <p>A task is assigned to one user or to one group. The group's members are copied onto the task when it is created, so
 * a later change to the group cannot widen who may act on a task already open. Every move is a conditional update on
 * {@code version}, which is the caller's {@code If-Match}: two people completing the same task both write, and only the
 * first reaches the row.
 *
 * @param outcomes the decisions the node accepts, at least one
 * @param escalateAt when the escalation group takes over, or null
 * @param expiresAt when the task expires and the node takes its {@code on_expire} edge, or null
 */
public record Task(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        UUID tokenId,
        String nodeId,
        String title,
        Object form,
        UUID assigneeUserId,
        UUID assigneeGroupId,
        TaskStatus status,
        List<String> outcomes,
        Instant dueAt,
        UUID escalationGroupId,
        Instant escalateAt,
        Instant expiresAt,
        String onExpire,
        String decision,
        Object output,
        String completedBy,
        String completedVia,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean open() {
        return status.open();
    }

    /** A user takes the task: only an OPEN task can be claimed, and only once. */
    public Task claimed(UUID userId, Instant now) {
        if (status != TaskStatus.OPEN) {
            throw new ConflictException("only an open task can be claimed");
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, userId, null, TaskStatus.CLAIMED, outcomes,
                dueAt, escalationGroupId, escalateAt, expiresAt, onExpire, decision, output, completedBy,
                completedVia, completedAt, createdAt, now, version + 1);
    }

    /** Completes with a decision and the form's output; the caller has already checked both. */
    public Task completed(String decision, Object output, String actor, String via, Instant now) {
        if (!status.open()) {
            throw new ConflictException("the task is already " + status.name().toLowerCase());
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, assigneeUserId, assigneeGroupId,
                TaskStatus.COMPLETED, outcomes, dueAt, escalationGroupId, escalateAt, expiresAt, onExpire, decision,
                output, actor, via, now, createdAt, now, version + 1);
    }

    /** Moves the task to another user or group, keeping the snapshot in step with the new assignment. */
    public Task reassigned(UUID userId, UUID groupId, Instant now) {
        if (!status.open()) {
            throw new ConflictException("only an open task can be reassigned");
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, userId, groupId, TaskStatus.OPEN, outcomes,
                dueAt, null, escalateAt, expiresAt, onExpire, decision, output, completedBy, completedVia,
                completedAt, createdAt, now, version + 1);
    }

    /** The due time passed: the escalation group takes over, and the status says so. */
    public Task escalated(Instant nextEscalation, Instant now) {
        if (!status.open() || status == TaskStatus.ESCALATED) {
            throw new ConflictException("only an open task escalates");
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, null, escalationGroupId, TaskStatus.ESCALATED,
                outcomes, dueAt, escalationGroupId, nextEscalation, expiresAt, onExpire, decision, output,
                completedBy, completedVia, completedAt, createdAt, now, version + 1);
    }

    /** The end of the line passed without a decision; the workflow takes its expiry branch. */
    public Task expired(Instant now) {
        if (!status.open()) {
            throw new ConflictException("only an open task expires");
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, assigneeUserId, assigneeGroupId,
                TaskStatus.EXPIRED, outcomes, dueAt, escalationGroupId, escalateAt, expiresAt, onExpire, decision,
                output, completedBy, completedVia, completedAt, createdAt, now, version + 1);
    }

    /** Cancelled when its instance is cancelled; an open task stops being actionable. */
    public Task cancelled(Instant now) {
        if (!status.open()) {
            return this;
        }
        return new Task(
                id, tenantId, instanceId, tokenId, nodeId, title, form, assigneeUserId, assigneeGroupId,
                TaskStatus.CANCELLED, outcomes, dueAt, escalationGroupId, escalateAt, expiresAt, onExpire, decision,
                output, completedBy, completedVia, completedAt, createdAt, now, version + 1);
    }

    /** The decisions a task accepts, as the node declared them. */
    public boolean accepts(String value) {
        return outcomes.contains(value);
    }

}

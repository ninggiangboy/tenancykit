package dev.ngb.workflow.tasks.application.task.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One task as the API returns it: what it asks, who it is for, when it is due and what was decided. */
public record TaskView(
        UUID id,
        String title,
        String status,
        UUID instanceId,
        String nodeId,
        String assigneeKind,
        String assignee,
        UUID assigneeUserId,
        List<String> outcomes,
        Object form,
        Instant dueAt,
        Instant escalateAt,
        Instant expiresAt,
        String decision,
        Object output,
        String completedBy,
        String completedVia,
        Instant completedAt,
        Instant createdAt,
        long version) {}

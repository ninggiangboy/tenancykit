package dev.ngb.workflow.tasks.domain.task.event;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The facts a human task announces. The same rule as every workflow event: ids and outcomes, never a person's data
 * beyond the id the Tenant assigned, and never the instance's input.
 */
public final class TaskEvents {

    private TaskEvents() {}

    public record Event(
            UUID eventId,
            TenantId tenantId,
            String type,
            String aggregate,
            Instant occurredAt,
            Map<String, Object> payload)
            implements DomainEvent {}

    public static Event created(Task task, UUID eventId, Instant at) {
        Map<String, Object> data = base(task);
        data.put(
                "assigneeKind", task.assigneeUserId() != null ? "user" : "group");
        data.put(
                "assignee",
                task.assigneeUserId() != null
                        ? task.assigneeUserId().toString()
                        : String.valueOf(task.assigneeGroupId()));
        data.put("outcomes", task.outcomes());
        data.put("createdAt", at.toString());
        return event(task, eventId, "task.created", data, at);
    }

    public static Event completed(Task task, UUID eventId, Instant at) {
        Map<String, Object> data = base(task);
        data.put("decision", task.decision());
        data.put("completedBy", task.completedBy());
        data.put("via", task.completedVia());
        data.put("completedAt", at.toString());
        return event(task, eventId, "task.completed", data, at);
    }

    public static Event escalated(Task task, UUID eventId, Instant at) {
        Map<String, Object> data = base(task);
        data.put("escalationGroup", String.valueOf(task.escalationGroupId()));
        data.put("escalatedAt", at.toString());
        return event(task, eventId, "task.escalated", data, at);
    }

    public static Event expired(Task task, UUID eventId, Instant at) {
        Map<String, Object> data = base(task);
        data.put("expiredAt", at.toString());
        return event(task, eventId, "task.expired", data, at);
    }

    private static Map<String, Object> base(Task task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.id().toString());
        data.put("instanceId", task.instanceId().toString());
        data.put("nodeId", task.nodeId());
        data.put("title", task.title());
        if (task.dueAt() != null) {
            data.put("dueAt", task.dueAt().toString());
        }
        return data;
    }

    private static Event event(Task task, UUID eventId, String type, Map<String, Object> data, Instant at) {
        return new Event(eventId, task.tenantId(), type, "task/" + task.id(), at, Map.copyOf(data));
    }
}

package dev.ngb.workflow.runtime.domain.event;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The facts a run announces. Each is written to the outbox in the same transaction as the state change, so a rolled-back
 * change sends nothing. A payload names ids, the definition code and its version, and a redacted reason; it never
 * carries the input, a step's output or a secret.
 */
public final class RuntimeEvents {

    private RuntimeEvents() {}

    /** One event as the outbox stores it; {@code type} is the stable name the schemas are keyed by. */
    public record Event(
            UUID eventId,
            TenantId tenantId,
            String type,
            String aggregate,
            Instant occurredAt,
            Map<String, Object> payload)
            implements DomainEvent {}

    public static Event started(Instance instance, String definitionCode, UUID eventId, Instant at) {
        Map<String, Object> data = base(instance, definitionCode);
        data.put("startedAt", at.toString());
        data.put("startedBy", instance.startedBy() == null ? "unknown" : instance.startedBy());
        return event(instance, eventId, "instance.started", data, at);
    }

    public static Event finished(
            Instance instance, String definitionCode, InstanceStatus status, UUID eventId, Instant at) {
        Map<String, Object> data = base(instance, definitionCode);
        data.put("finishedAt", at.toString());
        return event(instance, eventId, typeOf(status), data, at);
    }

    public static Event failed(
            Instance instance,
            String definitionCode,
            String errorClass,
            String message,
            UUID eventId,
            Instant at) {
        Map<String, Object> data = base(instance, definitionCode);
        data.put("status", InstanceStatus.FAILED.name());
        data.put("finishedAt", at.toString());
        data.put("errorClass", errorClass);
        data.put("message", message);
        return event(instance, eventId, "instance.failed", data, at);
    }

    public static Event stuck(Instance instance, String definitionCode, String reason, UUID eventId, Instant at) {
        Map<String, Object> data = base(instance, definitionCode);
        data.put("status", InstanceStatus.FAILED_STUCK.name());
        data.put("failureReason", reason);
        data.put("stuckAt", at.toString());
        return event(instance, eventId, "instance.stuck", data, at);
    }

    public static Event stepDeadLettered(
            Instance instance,
            String definitionCode,
            String nodeId,
            String errorClass,
            String message,
            UUID eventId,
            Instant at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instance.id().toString());
        data.put("definitionCode", definitionCode);
        data.put("version", instance.versionNumber());
        data.put("nodeId", nodeId);
        data.put("errorClass", errorClass);
        data.put("message", message);
        data.put("failedAt", at.toString());
        return event(instance, eventId, "step.dead-lettered", data, at);
    }

    private static String typeOf(InstanceStatus status) {
        return switch (status) {
            case COMPLETED -> "instance.completed";
            case CANCELLED -> "instance.cancelled";
            default -> "instance.failed";
        };
    }

    private static Map<String, Object> base(Instance instance, String definitionCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instance.id().toString());
        data.put("definitionCode", definitionCode);
        data.put("version", instance.versionNumber());
        data.put("status", instance.status().name());
        if (instance.correlationKey() != null) {
            data.put("correlationKey", instance.correlationKey());
        }
        return data;
    }

    private static Event event(Instance instance, UUID eventId, String type, Map<String, Object> data, Instant at) {
        return new Event(
                eventId, instance.tenantId(), type, "instance/" + instance.id(), at, Map.copyOf(data));
    }
}

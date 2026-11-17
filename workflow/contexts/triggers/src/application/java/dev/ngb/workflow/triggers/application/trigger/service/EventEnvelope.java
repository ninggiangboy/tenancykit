package dev.ngb.workflow.triggers.application.trigger.service;

import dev.ngb.core.error.BadRequestException;
import java.util.Map;
import java.util.UUID;

/**
 * The event envelope a suite app sends: {@code id}, {@code type}, {@code tenantId}, {@code occurredAt} and {@code data}.
 *
 * <p>The signature proves the delivery, and the producer's headers name the id and the type; the body supplies them when
 * a source does not sign them as headers. The whole document is kept as the payload so a wait can read any member.
 *
 * @param data the event's own object, which is the instance input when a trigger names none
 */
public record EventEnvelope(UUID eventId, String type, Object data, Object document) {

    public static EventEnvelope of(WebhookSignatures.Delivery delivery, Object document) {
        Map<String, Object> members = Documents.object(document);
        String id = delivery.eventId() != null ? delivery.eventId() : text(members, "id");
        String type = delivery.eventType() != null ? delivery.eventType() : text(members, "type");
        if (id == null || id.isBlank()) {
            throw new BadRequestException("an event names its id");
        }
        if (type == null || type.isBlank()) {
            throw new BadRequestException("an event names its type");
        }
        try {
            return new EventEnvelope(
                    UUID.fromString(id), type, members == null ? null : members.get("data"), document);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("an event id is a UUID");
        }
    }

    private static String text(Map<String, Object> members, String name) {
        return members == null ? null : Documents.text(members.get(name));
    }
}

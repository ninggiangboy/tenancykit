package dev.ngb.workflow.triggers.domain.trigger;

import java.time.Instant;
import java.util.UUID;

/**
 * One way a definition starts.
 *
 * @param source the app code of a suite webhook, the source name of an external one, or the trigger kind's own name
 * @param eventType the one event type that starts it, or null for every type of its source
 * @param filter an expression over the event envelope, or null to accept every event
 * @param correlation an expression for the instance's correlation key, or null
 * @param input an expression for the instance's input, or null to use the event's {@code data}
 * @param secretCiphertext the signing secret's envelope, only for a signed kind
 */
public record Trigger(
        UUID id,
        UUID tenantId,
        String name,
        TriggerKind kind,
        String source,
        String eventType,
        UUID definitionId,
        Object filter,
        Object correlation,
        Object input,
        String secretCiphertext,
        String keyId,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Trigger updated(
            String name, String eventType, Object filter, Object correlation, Object input, boolean enabled, Instant now) {
        return new Trigger(
                id, tenantId, name, kind, source, eventType, definitionId, filter, correlation, input,
                secretCiphertext, keyId, enabled, createdAt, now, version + 1);
    }
}

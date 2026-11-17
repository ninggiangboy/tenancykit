package dev.ngb.workflow.runtime.domain.wait;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A token parked on an event: the source and type it accepts, for how long, and whether it was already matched.
 *
 * <p>An arriving event resolves a subscription with one conditional update of the token; the subscription is then closed
 * so a redelivery cannot resume the same wait twice.
 */
public record WaitSubscription(
        UUID id,
        TenantId tenantId,
        UUID instanceId,
        UUID tokenId,
        String source,
        String eventType,
        Instant registeredAt,
        Instant expiresAt,
        Instant resolvedAt) {

    public WaitSubscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** The source that accepts any producer; a wait names it when the Tenant does not care who sends the event. */
    public static final String ANY_SOURCE = "any";

    public static WaitSubscription opening(
            UUID id,
            TenantId tenantId,
            UUID instanceId,
            UUID tokenId,
            String source,
            String eventType,
            Instant now,
            Instant expiresAt) {
        return new WaitSubscription(
                id, tenantId, instanceId, tokenId, source, eventType, now, expiresAt, null);
    }

    public boolean accepts(String eventSource, String eventType) {
        return resolvedAt == null
                && (ANY_SOURCE.equals(source) || source.equals(eventSource))
                && this.eventType.equals(eventType);
    }
}

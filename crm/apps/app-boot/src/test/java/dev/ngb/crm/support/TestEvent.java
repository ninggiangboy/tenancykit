package dev.ngb.crm.support;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An event a test publishes through the real outbox, shaped like one a business context will write. */
public record TestEvent(
        UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt, Map<String, Object> payload)
        implements DomainEvent {

    public static TestEvent customerCreated(TenantId tenantId) {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        return new TestEvent(
                id,
                tenantId,
                "customer.created",
                "customer/" + customerId,
                Instant.now(),
                Map.of("customerId", customerId.toString(), "externalRef", "cust-" + id, "createdAt", Instant.now().toString()));
    }
}

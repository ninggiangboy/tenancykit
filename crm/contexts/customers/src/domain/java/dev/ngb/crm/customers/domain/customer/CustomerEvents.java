package dev.ngb.crm.customers.domain.customer;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** What the customer feature announces. One place lists it, so a new fact is a new record here. */
public final class CustomerEvents {

    /** Announcement of a customer that did not exist before. Carries the identity, never the person's attributes. */
    public static final String CUSTOMER_CREATED = "customer.created";

    private CustomerEvents() {}

    /**
     * A customer now exists. The payload is the contract the Tenant's endpoints receive: the reference it chose, crm's
     * own id and the creation instant, and nothing that could identify the person.
     */
    public record Created(
            UUID eventId, TenantId tenantId, CustomerId customerId, String externalRef, Instant createdAt)
            implements DomainEvent {

        public Created {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(externalRef, "externalRef");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public String type() {
            return CUSTOMER_CREATED;
        }

        @Override
        public String aggregate() {
            return "customer/" + customerId;
        }

        @Override
        public Instant occurredAt() {
            return createdAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerId", customerId.toString());
            payload.put("externalRef", externalRef);
            payload.put("createdAt", createdAt.toString());
            return payload;
        }
    }
}

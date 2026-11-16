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

    /**
     * Announcement that a customer's personal data was erased. Carries crm's own id and nothing else, so a Tenant can
     * react to the erasure without crm sending the person's data along with it.
     */
    public static final String CUSTOMER_ERASED = "customer.erased";

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

    /**
     * A customer's personal data was erased, so its reference is free again and the same one may start a new customer.
     * The payload is the contract the Tenant's endpoints receive: crm's own id and the instant, and no attribute of
     * the person, because the point of the event is that crm no longer holds any.
     */
    public record Erased(UUID eventId, TenantId tenantId, CustomerId customerId, Instant erasedAt)
            implements DomainEvent {

        public Erased {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(erasedAt, "erasedAt");
        }

        @Override
        public String type() {
            return CUSTOMER_ERASED;
        }

        @Override
        public String aggregate() {
            return "customer/" + customerId;
        }

        @Override
        public Instant occurredAt() {
            return erasedAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerId", customerId.toString());
            return payload;
        }
    }
}

package dev.ngb.billing.customer.domain.customer;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What the customer context announces. Payloads are the integration contract. */
public final class CustomerEvents {

    private CustomerEvents() {}

    /** A Customer now exists. The payload is the integration contract; it carries no email or other personal data. */
    public record CustomerCreated(UUID eventId, Customer customer, Instant occurredAt) implements DomainEvent {

        public static final String TYPE = SuiteEventTypes.CUSTOMER_CREATED;

        @Override
        public TenantId tenantId() {
            return customer.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.customer(customer.id());
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerId", customer.id().toString());
            payload.put("displayName", customer.displayName());
            payload.put("identities", customer.identities().stream()
                    .map(identity -> Map.<String, Object>of(
                            "tenantSystemId", identity.tenantSystemId().toString(), "externalUserId", identity.externalUserId()))
                    .toList());
            return payload;
        }
    }
}

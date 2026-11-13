package dev.ngb.billing.pricing.domain.plan;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** What pricing announces. Payloads are the integration contract: ids, amounts in minor units and codes, no personal data. */
public final class PricingEvents {

    private PricingEvents() {}

    /** A new plan version exists (the first version of a new plan, or a later one). */
    public record PlanVersionPublished(UUID eventId, PricingPlan plan, PlanVersion version, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.PRICING_PLAN_VERSION_PUBLISHED;

        @Override
        public TenantId tenantId() {
            return plan.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.pricingPlan(plan.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("planId", plan.id().toString(), "code", plan.code(), "model", plan.model().name(), "currency", plan.currency().code(),
                    "version", version.number(), "billingCycle", version.billingCycle() == null ? null : version.billingCycle().name(),
                    "validFrom", version.validFrom().toString(), "validUntil", version.validUntil() == null ? null : version.validUntil().toString());
        }
    }

    public record PlanRetired(UUID eventId, PricingPlan plan, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.PRICING_PLAN_RETIRED;

        @Override
        public TenantId tenantId() {
            return plan.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.pricingPlan(plan.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("planId", plan.id().toString(), "code", plan.code());
        }
    }
}

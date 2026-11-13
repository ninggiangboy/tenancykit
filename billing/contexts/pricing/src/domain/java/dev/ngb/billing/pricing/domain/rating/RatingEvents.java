package dev.ngb.billing.pricing.domain.rating;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.billing.sharedkernel.event.MoneyEventPayload;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.AggregateTargets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** What rating announces. Payloads are the integration contract: ids, amounts in minor units and codes, no personal data. */
public final class RatingEvents {

    private RatingEvents() {}

    /** Usage was priced and accounted for: charged to the wallet, or rated to nothing. A rating whose charge failed announces {@code charge.failed} instead. */
    public record UsageRated(UUID eventId, UsageRating rating, Instant occurredAt) implements DomainEvent {
        public static final String TYPE = SuiteEventTypes.USAGE_RATED;

        @Override
        public TenantId tenantId() {
            return rating.tenantId();
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String aggregate() {
            return AggregateTargets.usageRating(rating.id());
        }

        @Override
        public Map<String, Object> payload() {
            return MoneyEventPayload.payload("ratingId", rating.id().toString(), "customerId", rating.customerId().toString(), "walletId", rating.walletId().toString(),
                    "planId", rating.planId().toString(), "planVersion", rating.planVersion(), "status", rating.status().name(),
                    "amount", MoneyEventPayload.money(rating.amount()),
                    "chargeId", rating.chargeId() == null ? null : rating.chargeId().toString(), "usageCount", rating.usageCount(),
                    "periodStart", rating.periodStart().toString(), "periodEnd", rating.periodEnd().toString());
        }
    }
}

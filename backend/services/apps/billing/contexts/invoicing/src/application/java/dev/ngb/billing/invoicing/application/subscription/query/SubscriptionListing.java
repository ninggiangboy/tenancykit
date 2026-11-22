package dev.ngb.billing.invoicing.application.subscription.query;

import dev.ngb.billing.invoicing.domain.subscription.SubscriptionStatus;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort subscriptions on. Newest first by default. */
public final class SubscriptionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("plan_id", FieldType.UUID),
            FieldSpec.of("plan_code", FieldType.STRING),
            FieldSpec.enumOf("cycle", BillingCycle.class),
            FieldSpec.enumOf("status", SubscriptionStatus.class),
            FieldSpec.of("anchor", FieldType.INSTANT),
            FieldSpec.of("periods_charged", FieldType.LONG),
            FieldSpec.of("cancel_at_period_end", FieldType.BOOLEAN),
            FieldSpec.of("canceled_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("next_renewal_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("renewal_attempts", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private SubscriptionListing() {}
}

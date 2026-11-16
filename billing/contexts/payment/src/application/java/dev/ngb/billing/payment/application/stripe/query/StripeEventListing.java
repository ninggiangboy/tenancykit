package dev.ngb.billing.payment.application.stripe.query;

import dev.ngb.billing.payment.domain.stripe.EventStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort Stripe events on. Newest received first by default. */
public final class StripeEventListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-received_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("event_id", FieldType.STRING),
            FieldSpec.of("type", FieldType.STRING),
            FieldSpec.enumOf("status", EventStatus.class),
            FieldSpec.of("attempts", FieldType.LONG),
            FieldSpec.of("next_attempt_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("last_error", FieldType.STRING).asNullable(),
            FieldSpec.of("received_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("processed_at", FieldType.INSTANT).asNullable());

    private StripeEventListing() {}
}

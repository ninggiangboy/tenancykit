package dev.ngb.billing.payment.application.refund.query;

import dev.ngb.billing.payment.domain.refund.RefundStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort refunds on, including the customer of the payment they refund. */
public final class RefundListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("payment_id", FieldType.UUID),
            FieldSpec.of("payment.customer_id", FieldType.UUID),
            FieldSpec.enumOf("status", RefundStatus.class),
            FieldSpec.of("amount", FieldType.LONG).asSortable(),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.of("reason", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("completed_at", FieldType.INSTANT).asNullable());

    private RefundListing() {}
}

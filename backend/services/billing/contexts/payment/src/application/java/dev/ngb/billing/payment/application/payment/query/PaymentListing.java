package dev.ngb.billing.payment.application.payment.query;

import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/**
 * What a caller may filter and sort payments on. Stripe identifiers, the idempotency key and the request hash are
 * internal and never listed. Newest first by default: payment ids are time-ordered, so {@code id} is the creation order.
 */
public final class PaymentListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.enumOf("status", PaymentStatus.class),
            FieldSpec.of("currency", FieldType.STRING).only(Op.EQ, Op.NEQ, Op.IN, Op.NIN),
            FieldSpec.of("amount", FieldType.LONG).asSortable(),
            FieldSpec.of("refunded_amount", FieldType.LONG),
            FieldSpec.of("invoice_id", FieldType.UUID).asNullable(),
            FieldSpec.of("ledger_entry_id", FieldType.UUID).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT),
            FieldSpec.of("completed_at", FieldType.INSTANT).asNullable());

    private PaymentListing() {}
}

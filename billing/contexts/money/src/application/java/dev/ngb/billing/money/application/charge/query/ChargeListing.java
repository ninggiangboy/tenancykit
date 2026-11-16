package dev.ngb.billing.money.application.charge.query;

import dev.ngb.billing.money.api.charge.ChargeSource;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort charges on, including the customer of the wallet they charge. */
public final class ChargeListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("wallet.customer_id", FieldType.UUID),
            FieldSpec.enumOf("source", ChargeSource.class),
            FieldSpec.enumOf("status", ChargeStatus.class),
            FieldSpec.of("amount", FieldType.LONG).asSortable(),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.of("reference", FieldType.STRING).asNullable(),
            FieldSpec.of("usage_ref", FieldType.STRING).asNullable(),
            FieldSpec.of("reservation_id", FieldType.UUID).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private ChargeListing() {}
}

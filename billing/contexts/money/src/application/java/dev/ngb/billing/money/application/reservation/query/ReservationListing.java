package dev.ngb.billing.money.application.reservation.query;

import dev.ngb.billing.money.api.reservation.ReservationStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort reservations on, including the customer of the wallet they hold budget on. */
public final class ReservationListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("wallet_id", FieldType.UUID),
            FieldSpec.of("wallet.customer_id", FieldType.UUID),
            FieldSpec.enumOf("status", ReservationStatus.class),
            FieldSpec.of("amount", FieldType.LONG),
            FieldSpec.of("captured_amount", FieldType.LONG),
            FieldSpec.of("expires_at", FieldType.INSTANT),
            FieldSpec.of("reference", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("closed_at", FieldType.INSTANT).asNullable());

    private ReservationListing() {}
}

package dev.ngb.billing.money.application.wallet.query;

import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort wallets on. Newest first by default. */
public final class WalletListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("customer_id", FieldType.UUID),
            FieldSpec.of("currency", FieldType.STRING),
            FieldSpec.enumOf("mode", WalletMode.class),
            FieldSpec.of("balance", FieldType.LONG),
            FieldSpec.of("held", FieldType.LONG),
            FieldSpec.of("credit_limit", FieldType.LONG),
            FieldSpec.of("last_sequence", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private WalletListing() {}
}

package dev.ngb.billing.money.application.ledger.query;

import dev.ngb.billing.money.api.ledger.EntryType;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort a wallet's statement on. The wallet is fixed by the path; the sequence orders it. */
public final class LedgerListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("seq", "seq",
            FieldSpec.of("id", FieldType.UUID),
            FieldSpec.of("seq", FieldType.LONG).asSortable(),
            FieldSpec.enumOf("type", EntryType.class),
            FieldSpec.of("amount", FieldType.LONG),
            FieldSpec.of("reference", FieldType.STRING),
            FieldSpec.of("reservation_id", FieldType.UUID).asNullable(),
            FieldSpec.of("charge_id", FieldType.UUID).asNullable(),
            FieldSpec.of("reversal_of", FieldType.UUID).asNullable(),
            FieldSpec.of("actor", FieldType.STRING),
            FieldSpec.of("reason", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private LedgerListing() {}
}

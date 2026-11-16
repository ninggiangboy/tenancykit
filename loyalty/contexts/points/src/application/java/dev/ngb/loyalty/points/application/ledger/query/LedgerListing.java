package dev.ngb.loyalty.points.application.ledger.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort a member's ledger on. Newest first, which is the order a statement reads. */
public final class LedgerListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("kind", FieldType.STRING),
            FieldSpec.of("points", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private LedgerListing() {}
}

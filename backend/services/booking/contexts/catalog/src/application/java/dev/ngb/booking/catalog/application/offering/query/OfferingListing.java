package dev.ngb.booking.catalog.application.offering.query;

import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the offering list by: the code, the name, the kind, the status and the two
 * instants. The list is newest first and the id breaks the tie, so the cursor is a range scan on one index.
 */
public final class OfferingListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.enumOf("kind", OfferingKind.class),
            FieldSpec.enumOf("status", OfferingStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private OfferingListing() {}
}

package dev.ngb.booking.catalog.application.resource.query;

import dev.ngb.booking.catalog.domain.resource.ResourceStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the resource list by. The id makes any ordering total, so it is the tie-breaker,
 * and the list defaults to newest first, which is the order the keyset index serves.
 */
public final class ResourceListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("zone", FieldType.STRING),
            FieldSpec.enumOf("status", ResourceStatus.class),
            FieldSpec.of("seats", FieldType.LONG).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private ResourceListing() {}
}

package dev.ngb.crm.customers.application.field.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the definitions by. The key identifies a definition and makes any ordering total,
 * so it is the tie-breaker and the default order; archived definitions are listed like the rest.
 */
public final class FieldDefinitionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("key", "key",
            FieldSpec.of("key", FieldType.STRING).asSortable(),
            FieldSpec.of("label", FieldType.STRING),
            FieldSpec.enumOf("type", dev.ngb.crm.customers.domain.field.FieldType.class),
            FieldSpec.of("archived", FieldType.BOOLEAN),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private FieldDefinitionListing() {}
}

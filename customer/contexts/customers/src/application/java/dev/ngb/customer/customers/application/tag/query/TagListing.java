package dev.ngb.customer.customers.application.tag.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow the tag listing by. A tag is the only key of the listing and its own order, so it is both
 * the sort and the tie-breaker of the cursor.
 */
public final class TagListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("tag", "tag",
            FieldSpec.of("tag", FieldType.STRING).asSortable());

    private TagListing() {}
}

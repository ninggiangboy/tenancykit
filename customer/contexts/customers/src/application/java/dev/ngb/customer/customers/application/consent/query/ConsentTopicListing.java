package dev.ngb.customer.customers.application.consent.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the consent topics by. The key identifies a topic and makes any ordering total, so
 * it is the tie-breaker and the default order; archived topics are listed like the rest.
 */
public final class ConsentTopicListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("key", "key",
            FieldSpec.of("key", FieldType.STRING).asSortable(),
            FieldSpec.of("label", FieldType.STRING),
            FieldSpec.of("archived", FieldType.BOOLEAN));

    private ConsentTopicListing() {}
}

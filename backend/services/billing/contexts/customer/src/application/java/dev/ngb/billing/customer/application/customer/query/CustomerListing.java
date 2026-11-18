package dev.ngb.billing.customer.application.customer.query;

import dev.ngb.billing.customer.domain.customer.CustomerStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort customers on. Oldest first by default, as the tenant has always read them. */
public final class CustomerListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("display_name", FieldType.STRING).asSortable(),
            FieldSpec.of("email", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", CustomerStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private CustomerListing() {}
}

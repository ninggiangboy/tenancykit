package dev.ngb.crm.customers.application.customer.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.crm.customers.domain.customer.CustomerStatus;

/**
 * What a caller may narrow and sort the customer list by. The attributes a Tenant sends are filterable but not
 * sortable — sorting follows the keyset index, newest first, and the id breaks the tie — and the search over a phone,
 * an email or a name that the list endpoint is meant to offer is not part of this schema yet, so a caller that sends a
 * filter this phase does not know about is told rather than silently served an unfiltered page.
 */
public final class CustomerListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("external_ref", FieldType.STRING),
            FieldSpec.of("display_name", FieldType.STRING).asNullable(),
            FieldSpec.of("phone", FieldType.STRING).asNullable(),
            FieldSpec.of("email", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", CustomerStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private CustomerListing() {}
}

package dev.ngb.booking.catalog.application.session.query;

import dev.ngb.booking.catalog.domain.session.SessionStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the session list by: the offering, the status and the start instant. The list
 * defaults to the earliest start first and the id breaks the tie, so the cursor is a range scan on one index.
 */
public final class SessionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "starts_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("offering_id", FieldType.UUID),
            FieldSpec.enumOf("status", SessionStatus.class),
            FieldSpec.of("starts_at", FieldType.INSTANT).asSortable());

    private SessionListing() {}
}

package dev.ngb.booking.booking.application.booking.query;

import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may narrow and sort the booking list by: the status, the offering, the customer reference, the source
 * and the starts-at instant. The list is newest first and the id breaks the tie, so the cursor is a range scan on one
 * index.
 */
public final class BookingListing {

    public static final FilterSchema SCHEMA = FilterSchema.of(
            "id",
            "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("status", BookingStatus.class),
            FieldSpec.of("offering_id", FieldType.UUID),
            FieldSpec.of("customer_ref", FieldType.STRING),
            FieldSpec.enumOf("source", BookingSource.class),
            FieldSpec.of("starts_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private BookingListing() {}
}

package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort receivers on. Newest first by default; receiver ids are time-ordered. */
public final class ReceiverListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("email", FieldType.STRING).asSortable(),
            FieldSpec.of("first_name", FieldType.STRING).asNullable(),
            FieldSpec.of("last_name", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", "SUBSCRIBED", "UNSUBSCRIBED"),
            FieldSpec.of("version", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private ReceiverListing() {}
}

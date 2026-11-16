package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort receiver groups on. Newest first by default. */
public final class GroupListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("version", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private GroupListing() {}
}

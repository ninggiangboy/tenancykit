package dev.ngb.mailroom.content.application.template.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort templates on. Newest first by default; template ids are time-ordered. */
public final class TemplateListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("key", FieldType.STRING).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.of("current_revision", FieldType.LONG),
            FieldSpec.of("version", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private TemplateListing() {}
}

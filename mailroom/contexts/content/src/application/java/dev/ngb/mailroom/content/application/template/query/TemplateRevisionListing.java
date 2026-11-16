package dev.ngb.mailroom.content.application.template.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort one template's revisions on. Newest revision first by default. */
public final class TemplateRevisionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-revision",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("revision", FieldType.LONG).asSortable(),
            FieldSpec.of("subject", FieldType.STRING),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private TemplateRevisionListing() {}
}

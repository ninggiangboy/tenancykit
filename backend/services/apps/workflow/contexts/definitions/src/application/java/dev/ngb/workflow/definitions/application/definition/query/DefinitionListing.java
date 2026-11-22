package dev.ngb.workflow.definitions.application.definition.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.workflow.definitions.domain.definition.DefinitionStatus;

/** What a caller may filter and sort definitions on. Newest first by default. */
public final class DefinitionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("code", FieldType.STRING),
            FieldSpec.of("name", FieldType.STRING),
            FieldSpec.enumOf("status", DefinitionStatus.class),
            FieldSpec.of("latest_version", FieldType.LONG).asSortable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT).asSortable());

    private DefinitionListing() {}
}

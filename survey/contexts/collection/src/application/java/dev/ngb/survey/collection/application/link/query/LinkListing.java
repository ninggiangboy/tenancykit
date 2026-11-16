package dev.ngb.survey.collection.application.link.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.survey.collection.domain.link.LinkKind;
import dev.ngb.survey.collection.domain.link.LinkStatus;

/** What a caller may filter and sort a survey's links on. Newest first by default. */
public final class LinkListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("survey_id", FieldType.UUID),
            FieldSpec.enumOf("kind", LinkKind.class),
            FieldSpec.of("source", FieldType.STRING),
            FieldSpec.of("external_ref", FieldType.STRING).asNullable(),
            FieldSpec.of("expires_at", FieldType.INSTANT).asNullable(),
            FieldSpec.enumOf("status", LinkStatus.class),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private LinkListing() {}
}

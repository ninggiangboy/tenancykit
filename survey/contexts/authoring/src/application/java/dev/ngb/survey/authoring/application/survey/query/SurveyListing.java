package dev.ngb.survey.authoring.application.survey.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;

/**
 * What a caller may filter and sort surveys on. A survey's optimistic-lock version and its current version pointer are
 * internal bookkeeping a client only reads on the detail, so neither is listed. Newest first by default.
 */
public final class SurveyListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).with(Op.CONTAINS).asSortable(),
            FieldSpec.enumOf("status", SurveyStatus.class),
            FieldSpec.of("current_version_id", FieldType.UUID).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT).asSortable());

    private SurveyListing() {}
}

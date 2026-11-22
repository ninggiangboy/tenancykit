package dev.ngb.survey.insight.application.export.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.survey.insight.domain.export.ExportStatus;

/**
 * What a caller may filter and sort a survey's exports on. The stored filter is the shape of the request, not a column
 * a client narrows by, so it is not listed. Newest first by default.
 */
public final class ExportListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("survey_id", FieldType.UUID),
            FieldSpec.enumOf("status", ExportStatus.class),
            FieldSpec.of("object_key", FieldType.STRING).asNullable(),
            FieldSpec.of("rows", FieldType.LONG).asNullable(),
            FieldSpec.of("requested_by", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("finished_at", FieldType.INSTANT).asNullable());

    private ExportListing() {}
}

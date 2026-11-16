package dev.ngb.survey.authoring.application.version.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may filter and sort a survey's published versions on. The document itself is not filterable: a version
 * list is a history, and a caller that wants the questions reads one version. Newest version first by default.
 */
public final class VersionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-version_no",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("survey_id", FieldType.UUID),
            FieldSpec.of("version_no", FieldType.LONG).asSortable(),
            FieldSpec.of("published_at", FieldType.INSTANT),
            FieldSpec.of("published_by", FieldType.STRING).asNullable());

    private VersionListing() {}
}

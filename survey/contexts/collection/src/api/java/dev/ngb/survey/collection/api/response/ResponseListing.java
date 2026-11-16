package dev.ngb.survey.collection.api.response;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may filter and sort a survey's responses on.
 *
 * <p>It sits in {@code collection}'s api package rather than in {@code insight}, which serves the endpoint, because
 * {@code collection} owns the {@code responses} rows the listing selects and is where the columns are bound. A listing
 * answers with completed responses only, so the submission state is the scope rather than a filter: {@code submitted_at}
 * is the field a caller narrows a report window by. Newest started first by default.
 */
public final class ResponseListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-started_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("survey_id", FieldType.UUID),
            FieldSpec.of("version_id", FieldType.UUID),
            FieldSpec.of("locale", FieldType.STRING).asNullable(),
            FieldSpec.of("source", FieldType.STRING),
            FieldSpec.of("respondent_identity", FieldType.STRING).asNullable(),
            FieldSpec.of("external_ref", FieldType.STRING).asNullable(),
            FieldSpec.of("score", FieldType.DECIMAL).asNullable(),
            FieldSpec.of("started_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("submitted_at", FieldType.INSTANT).asNullable());

    private ResponseListing() {}
}

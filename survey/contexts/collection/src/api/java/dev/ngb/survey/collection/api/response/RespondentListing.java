package dev.ngb.survey.collection.api.response;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/**
 * What a caller may filter and sort a survey's respondents on.
 *
 * <p>A respondent is an arrival, not an account: the listing is over {@code visits}, which {@code collection} owns, so
 * the schema sits in its api package next to the reader that selects it. The state values are the visit states the
 * table's check constraint admits. Most recent activity first by default.
 */
public final class RespondentListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-last_activity_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("link_id", FieldType.UUID),
            FieldSpec.of("source", FieldType.STRING),
            FieldSpec.enumOf("state", "OPENED", "STARTED", "COMPLETED", "ABANDONED"),
            FieldSpec.of("country", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("device_class", "DESKTOP", "MOBILE", "TABLET", "UNKNOWN"),
            FieldSpec.enumOf("traffic_class", "HUMAN", "BOT"),
            FieldSpec.of("response_id", FieldType.UUID).asNullable(),
            FieldSpec.of("opened_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("last_activity_at", FieldType.INSTANT).asSortable());

    private RespondentListing() {}
}

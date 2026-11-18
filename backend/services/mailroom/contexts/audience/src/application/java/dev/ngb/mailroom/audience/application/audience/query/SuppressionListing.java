package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort suppressions on. Ordered by normalized address by default. */
public final class SuppressionListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("email_normalized", "email_normalized",
            FieldSpec.of("email_normalized", FieldType.STRING).asSortable(),
            FieldSpec.enumOf("scope", "MARKETING", "ALL"),
            FieldSpec.enumOf("reason", "UNSUBSCRIBED", "MANUAL"),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private SuppressionListing() {}
}

package dev.ngb.billing.webhook.application.endpoint.query;

import dev.ngb.billing.webhook.domain.endpoint.EndpointStatus;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;

/** What a caller may filter and sort webhook endpoints on. Oldest first by default. */
public final class EndpointListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "created_at",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("url", FieldType.STRING),
            FieldSpec.of("description", FieldType.STRING).asNullable(),
            FieldSpec.enumOf("status", EndpointStatus.class),
            FieldSpec.of("key_id", FieldType.STRING),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT),
            FieldSpec.of("secret_rotated_at", FieldType.INSTANT).asNullable());

    private EndpointListing() {}
}

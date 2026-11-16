package dev.ngb.mailroom.sending.application.email.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/** What a caller may filter and sort the sent-email ledger on. Newest first by default. */
public final class EmailListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("kind", "TRANSACTIONAL", "MARKETING"),
            FieldSpec.enumOf("status", "QUEUED", "SENT", "FAILED", "SKIPPED"),
            FieldSpec.of("status_reason", FieldType.STRING).asNullable(),
            FieldSpec.of("to_address", FieldType.STRING).with(Op.CONTAINS),
            FieldSpec.of("campaign_id", FieldType.UUID).asNullable(),
            FieldSpec.of("receiver_id", FieldType.UUID).asNullable(),
            FieldSpec.of("template_id", FieldType.UUID).asNullable(),
            FieldSpec.of("template_revision", FieldType.LONG).asNullable(),
            FieldSpec.of("from_key", FieldType.STRING),
            FieldSpec.of("subject", FieldType.STRING),
            FieldSpec.of("body_purged_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("delivery_updated_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private EmailListing() {}
}

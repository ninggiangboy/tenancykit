package dev.ngb.mailroom.sending.application.campaign.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;

/** What a caller may filter and sort campaigns on. Newest first by default; campaign ids are time-ordered. */
public final class CampaignListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("name", FieldType.STRING).asSortable(),
            FieldSpec.enumOf("status", "DRAFT", "SCHEDULED", "SENDING", "PAUSED", "COMPLETED", "CANCELLED"),
            FieldSpec.of("template_id", FieldType.UUID),
            FieldSpec.of("template_revision", FieldType.LONG),
            FieldSpec.enumOf("target_type", "GROUP", "SEGMENT"),
            FieldSpec.of("target_id", FieldType.UUID),
            FieldSpec.of("from_key", FieldType.STRING),
            FieldSpec.of("from_name", FieldType.STRING).asNullable(),
            FieldSpec.of("reply_to", FieldType.STRING).asNullable(),
            FieldSpec.of("scheduled_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("started_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("completed_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("version", FieldType.LONG),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("updated_at", FieldType.INSTANT));

    private CampaignListing() {}
}

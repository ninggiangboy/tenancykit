package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.notification.dispatch.domain.notification.EmailDispatchStatus;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;

/**
 * What an auditor may filter and sort the dispatch ledger on. The tenant is the request's, never a parameter.
 *
 * <p>{@code recipient} offers {@code contains} because "every mail that went to this address" is the question the
 * ledger exists to answer; the column carries a trigram index for it. Newest first by default: the id is a UUID v7, so
 * it is the creation order and the cursor at once.
 */
public final class DispatchListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.of("source_event_id", FieldType.UUID),
            FieldSpec.enumOf("notification_type", NotificationType.class),
            FieldSpec.of("user_id", FieldType.UUID),
            FieldSpec.of("recipient", FieldType.STRING).asNullable().with(Op.CONTAINS),
            FieldSpec.of("template_key", FieldType.STRING),
            FieldSpec.enumOf("status", EmailDispatchStatus.class),
            FieldSpec.of("reason", FieldType.STRING).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
            FieldSpec.of("published_at", FieldType.INSTANT).asNullable());

    private DispatchListing() {}
}

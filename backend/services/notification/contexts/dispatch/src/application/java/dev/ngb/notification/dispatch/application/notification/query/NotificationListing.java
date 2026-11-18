package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;

/**
 * What a caller may filter and sort their own inbox on. The owner is not a field: the listing is always scoped to the
 * tenant of the request and the user of the token, so another inbox is not expressible.
 *
 * <p>{@code read_at} replaces the old {@code unread} flag — unread rows are {@code filter[read_at][is_null]=true}. The
 * id is a UUID v7, so it is the creation order and the cursor at once; newest first by default.
 */
public final class NotificationListing {

    public static final FilterSchema SCHEMA = FilterSchema.of("id", "-id",
            FieldSpec.of("id", FieldType.UUID).asSortable(),
            FieldSpec.enumOf("type", NotificationType.class),
            FieldSpec.of("template_key", FieldType.STRING),
            FieldSpec.of("locale", FieldType.STRING),
            FieldSpec.of("title", FieldType.STRING),
            FieldSpec.of("read_at", FieldType.INSTANT).asNullable(),
            FieldSpec.of("created_at", FieldType.INSTANT).asSortable());

    private NotificationListing() {}
}

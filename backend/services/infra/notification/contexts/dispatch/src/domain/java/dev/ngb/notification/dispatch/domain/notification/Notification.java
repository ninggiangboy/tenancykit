package dev.ngb.notification.dispatch.domain.notification;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One rendered in-app notification. A plain immutable class: no setters, and reading is a copy, so the row's read
 * state cannot be edited in place by a caller that never committed it.
 */
public record Notification(
        NotificationId id,
        TenantId tenantId,
        UUID userId,
        NotificationType type,
        UUID sourceEventId,
        String sourceEventType,
        String templateKey,
        int templateVersion,
        String locale,
        String title,
        String body,
        String link,
        Instant readAt,
        Instant createdAt) {

    public Notification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(sourceEventType, "sourceEventType");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean unread() {
        return readAt == null;
    }

    /** The same notification, marked read; marking an already-read notification is a no-op. */
    public Notification markRead(Instant at) {
        return readAt == null
                ? new Notification(
                        id, tenantId, userId, type, sourceEventId, sourceEventType, templateKey, templateVersion,
                        locale, title, body, link, at, createdAt)
                : this;
    }
}

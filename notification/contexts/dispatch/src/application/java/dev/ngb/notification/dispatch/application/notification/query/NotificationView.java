package dev.ngb.notification.dispatch.application.notification.query;

import java.time.Instant;
import java.util.UUID;

/** One row of the caller's own list; shaped for the response and never built from a domain aggregate. */
public record NotificationView(
        UUID id,
        String type,
        String templateKey,
        int templateVersion,
        String locale,
        String title,
        String body,
        String link,
        Instant readAt,
        Instant createdAt) {}

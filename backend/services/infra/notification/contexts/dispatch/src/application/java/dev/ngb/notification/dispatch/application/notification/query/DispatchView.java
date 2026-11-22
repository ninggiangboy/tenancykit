package dev.ngb.notification.dispatch.application.notification.query;

import java.time.Instant;
import java.util.UUID;

/** One ledger row for the auditor; the recipient is returned only to a caller holding the dispatch permission. */
public record DispatchView(
        UUID id,
        UUID sourceEventId,
        String notificationType,
        UUID userId,
        String recipient,
        String fromKey,
        String templateKey,
        int templateVersion,
        String locale,
        String subject,
        String status,
        String reason,
        Instant createdAt,
        Instant publishedAt) {}

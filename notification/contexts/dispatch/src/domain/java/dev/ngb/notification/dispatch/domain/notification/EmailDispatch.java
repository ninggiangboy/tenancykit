package dev.ngb.notification.dispatch.domain.notification;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/** One row of the dispatch ledger: the answer to "why was or was not this user emailed". */
public record EmailDispatch(
        UUID id,
        TenantId tenantId,
        UUID sourceEventId,
        NotificationType notificationType,
        UUID userId,
        String recipient,
        String requestId,
        String fromKey,
        String templateKey,
        int templateVersion,
        String locale,
        String subject,
        EmailDispatchStatus status,
        String reason,
        Instant createdAt,
        Instant publishedAt) {}

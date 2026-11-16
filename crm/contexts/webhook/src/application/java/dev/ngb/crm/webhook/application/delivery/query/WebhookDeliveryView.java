package dev.ngb.crm.webhook.application.delivery.query;

import dev.ngb.crm.webhook.domain.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

/** A delivery as the history endpoint shows it. */
public record WebhookDeliveryView(
        UUID id,
        UUID endpointId,
        UUID eventId,
        String eventType,
        String body,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt,
        Instant completedAt) {}

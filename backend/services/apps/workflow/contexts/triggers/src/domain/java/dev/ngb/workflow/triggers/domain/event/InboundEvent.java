package dev.ngb.workflow.triggers.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * One event a signed hook delivered, kept for the early-event window so a wait that registers after the event still
 * sees it.
 *
 * @param eventId the producer's id, which is what makes a redelivery a duplicate
 * @param payload the whole envelope, as the producer sent it
 */
public record InboundEvent(
        UUID id,
        UUID tenantId,
        String source,
        UUID eventId,
        String eventType,
        Object payload,
        Instant receivedAt,
        Instant expiresAt) {}

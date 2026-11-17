package dev.ngb.workflow.runtime.domain.step;

import java.time.Instant;
import java.util.UUID;

/**
 * An event that already arrived, so a wait that registers afterwards still sees it.
 *
 * @param eventId the producer's id, which is what makes a redelivery a duplicate
 * @param payload the event's own data member, which is what the resumed wait uses as its response
 */
public record BufferedEvent(UUID eventId, String eventType, Object payload, Instant receivedAt) {}

package dev.ngb.workflow.runtime.domain.step;

import java.time.Instant;
import java.util.Optional;

/**
 * The early-event window: an event that arrived before a wait registered is still readable.
 *
 * <p>The engine only gives the wait the event that arrived after the instance started, because an older event belongs to
 * a run that had not begun. The query is by source and type; the wait decides whether the payload is the one it wanted.
 */
public interface BufferedEventLookup {

    Optional<BufferedEvent> earliest(String source, String eventType, Instant receivedAfter);
}

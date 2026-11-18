package dev.ngb.workflow.triggers.application.trigger.port;

import dev.ngb.workflow.triggers.domain.event.InboundEvent;
import java.time.Instant;
import java.util.Optional;

/** The event buffer the early-event window is built on. */
public interface InboundEventRepository {

    /** Inserts the event unless its source already delivered that id; false means it was a duplicate. */
    boolean insertIfAbsent(InboundEvent event);

    /**
     * The earliest event of this source and type received at or after the given instant, or empty.
     *
     * @param source the producer, or {@code any} to accept every source
     */
    Optional<InboundEvent> earliestMatch(String source, String eventType, Instant receivedAfter);
}

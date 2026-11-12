package dev.ngb.core.messaging.inbound;

import dev.ngb.core.event.EventEnvelope;

/** One consumer's interest in the events arriving on its queue. */
public interface InboundEventHandler {

    /** The inbox key: one consumer name per handler, so two handlers never share a deduplication row. */
    String consumer();

    /** Whether this handler wants the event type. A message no handler accepts is acknowledged and ignored. */
    boolean accepts(String type);

    /** Applies the event; throwing rolls back the whole unit of work and asks for a retry. */
    void handle(EventEnvelope event);
}

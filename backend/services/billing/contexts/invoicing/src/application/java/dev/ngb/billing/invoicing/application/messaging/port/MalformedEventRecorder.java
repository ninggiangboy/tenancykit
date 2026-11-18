package dev.ngb.billing.invoicing.application.messaging.port;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.billing.sharedkernel.event.MalformedEventException;

/**
 * Where a consumer reports an event whose payload it cannot decode, once the transaction that dropped it has
 * committed. The application layer has no logger, so the implementation lives in the application boot and writes the
 * WARN line naming the consumer and the event, and counts it.
 */
@FunctionalInterface
public interface MalformedEventRecorder {

    void record(String consumer, DomainEvent event, MalformedEventException malformed);

    /** Keeps a unit test that builds a consumer by hand readable. */
    MalformedEventRecorder NONE = (consumer, event, malformed) -> {};
}

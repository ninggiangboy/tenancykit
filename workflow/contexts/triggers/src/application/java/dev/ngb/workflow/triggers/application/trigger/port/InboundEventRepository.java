package dev.ngb.workflow.triggers.application.trigger.port;

import dev.ngb.workflow.triggers.domain.event.InboundEvent;

/** The event buffer the early-event window is built on. */
public interface InboundEventRepository {

    /** Inserts the event unless its source already delivered that id; false means it was a duplicate. */
    boolean insertIfAbsent(InboundEvent event);
}

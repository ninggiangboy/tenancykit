package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.domain.step.BufferedEvent;
import dev.ngb.workflow.runtime.domain.step.BufferedEventLookup;
import dev.ngb.workflow.triggers.application.trigger.port.InboundEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Gives the runtime the early-event window without a context dependency: the buffer belongs to the triggers context, and
 * boot is the only place allowed to know both sides.
 */
class TriggeredEventLookup implements BufferedEventLookup {

    private final UnitOfWork unitOfWork;
    private final InboundEventRepository events;

    TriggeredEventLookup(UnitOfWork unitOfWork, InboundEventRepository events) {
        this.unitOfWork = unitOfWork;
        this.events = events;
    }

    @Override
    public Optional<BufferedEvent> earliest(String source, String eventType, Instant receivedAfter) {
        return unitOfWork
                .transactional(() -> events.earliestMatch(source, eventType, receivedAfter))
                .map(event -> new BufferedEvent(
                        event.eventId(), event.eventType(), data(event.payload()), event.receivedAt()));
    }

    private static Object data(Object payload) {
        return payload instanceof Map<?, ?> envelope ? envelope.get("data") : payload;
    }
}

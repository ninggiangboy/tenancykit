package dev.ngb.core.replica;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import java.util.Map;

/** The inbox consumer that keeps the replica current from every {@code *.changed} stream {@link ReplicaStream} lists. */
public class ReplicaEventHandler implements InboundEventHandler {

    /** One consumer name for the replica, so a redelivery never applies twice. */
    public static final String CONSUMER = "platform-events-replica";

    private final ReplicaWriter writer;

    public ReplicaEventHandler(ReplicaWriter writer) {
        this.writer = writer;
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean accepts(String type) {
        return ReplicaStream.byEventType(type).isPresent();
    }

    @Override
    public void handle(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        writer.apply(event.type(), payload);
    }
}

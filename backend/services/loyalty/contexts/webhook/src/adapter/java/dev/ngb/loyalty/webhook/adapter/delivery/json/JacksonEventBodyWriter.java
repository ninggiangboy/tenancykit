package dev.ngb.loyalty.webhook.adapter.delivery.json;

import dev.ngb.loyalty.webhook.application.delivery.port.EventBodyWriter;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.messaging.broker.EventDocument;
import tools.jackson.databind.json.JsonMapper;

/**
 * The delivered document: the one canonical {@link EventDocument} the broker also sends, so a webhook receiver gets
 * byte for byte what a consumer sees on AMQP. `data` is the event's own payload, whose shape is owned by the schema
 * of that event type in {@code contracts/events}.
 */
public class JacksonEventBodyWriter implements EventBodyWriter {

    private final EventDocument document;

    public JacksonEventBodyWriter(JsonMapper json) {
        this.document = new EventDocument(json);
    }

    @Override
    public String write(DomainEvent event) {
        return document.write(event);
    }
}

package dev.ngb.core.messaging.broker;

import dev.ngb.core.event.EventEnvelope;
import tools.jackson.databind.json.JsonMapper;

/**
 * The broker's view of the AMQP body, exactly as documented in {@code contracts/messaging/README.md}. It writes and
 * reads the one canonical {@link EventDocument}, so the broker and the webhook body writer can never drift apart.
 */
public final class EnvelopeCodec {

    /** Bumped only when the envelope itself changes incompatibly. */
    public static final int SCHEMA_VERSION = EventDocument.SCHEMA_VERSION;

    private final EventDocument document;

    public EnvelopeCodec(JsonMapper json) {
        this.document = new EventDocument(json);
    }

    public EnvelopeCodec(JsonMapper json, int schemaVersion) {
        this.document = new EventDocument(json, schemaVersion);
    }

    public byte[] encode(EventEnvelope event) {
        return document.encode(event);
    }

    public EventEnvelope decode(byte[] body) {
        return document.decode(body);
    }
}

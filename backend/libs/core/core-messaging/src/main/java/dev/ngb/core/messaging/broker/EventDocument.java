package dev.ngb.core.messaging.broker;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one canonical envelope. The producer encodes once and the bytes travel untouched over AMQP, and a webhook
 * receiver gets exactly the same document, so a forwarder never re-serialises and never changes a payload. The
 * fields are camelCase: {@code id}, {@code type}, {@code aggregate}, {@code tenantId}, {@code occurredAt},
 * {@code schemaVersion} and {@code data}.
 *
 * <p>{@code schemaVersion} is bumped only when the envelope itself changes incompatibly and a reader rejects any
 * other version, so an old consumer never silently misreads a newer document.
 */
public final class EventDocument {

    /** The envelope version every producer emits and every consumer reads; bumped only on an incompatible change. */
    public static final int SCHEMA_VERSION = 2;

    private final JsonMapper json;
    private final int schemaVersion;

    public EventDocument(JsonMapper json) {
        this(json, SCHEMA_VERSION);
    }

    public EventDocument(JsonMapper json, int schemaVersion) {
        this.json = json;
        this.schemaVersion = schemaVersion;
    }

    public byte[] encode(DomainEvent event) {
        return json.writeValueAsBytes(new Wire(
                event.eventId(), event.type(), event.aggregate(), event.tenantId().value(), event.occurredAt().toString(),
                schemaVersion, event.payload()));
    }

    /** @return the document as UTF-8 JSON text, byte for byte what {@link #encode} writes */
    public String write(DomainEvent event) {
        return new String(encode(event), StandardCharsets.UTF_8);
    }

    public EventEnvelope decode(byte[] body) {
        Wire wire;
        try {
            wire = json.readValue(body, Wire.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the message body is not a suite envelope", e);
        }
        if (wire.schemaVersion() != schemaVersion) {
            throw new IllegalArgumentException("unsupported suite envelope schema_version " + wire.schemaVersion()
                    + ", this consumer reads " + schemaVersion);
        }
        if (wire.id() == null || wire.type() == null || wire.aggregate() == null || wire.tenantId() == null || wire.occurredAt() == null) {
            throw new IllegalArgumentException("the suite envelope is missing id, type, aggregate, tenantId or occurredAt");
        }
        Map<String, Object> data = wire.data() == null ? Map.of() : wire.data();
        return new EventEnvelope(
                wire.id(), new TenantId(wire.tenantId()), wire.type(), wire.aggregate(), Instant.parse(wire.occurredAt()), data);
    }

    private record Wire(UUID id, String type, String aggregate, UUID tenantId, String occurredAt, int schemaVersion, Map<String, Object> data) {}
}

package dev.ngb.core.messaging.broker;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AMQP body, exactly as documented in {@code contracts/messaging/README.md}. The producer encodes once and the
 * bytes travel untouched to every consumer, so a forwarder never re-serialises and never changes a payload.
 */
public final class EnvelopeCodec {

    /** Bumped only when the envelope itself changes incompatibly. */
    public static final int SCHEMA_VERSION = 1;

    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {};

    private final JsonMapper json;

    public EnvelopeCodec(JsonMapper json) {
        this.json = json;
    }

    public byte[] encode(EventEnvelope event) {
        return json.writeValueAsBytes(new Wire(
                event.eventId(), event.type(), event.tenantId().value(), event.occurredAt().toString(), SCHEMA_VERSION, event.payload()));
    }

    public EventEnvelope decode(byte[] body) {
        Wire wire;
        try {
            wire = json.readValue(body, Wire.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the message body is not a suite envelope", e);
        }
        if (wire.id() == null || wire.type() == null || wire.tenant_id() == null || wire.occurred_at() == null) {
            throw new IllegalArgumentException("the suite envelope is missing id, type, tenant_id or occurred_at");
        }
        Map<String, Object> data = wire.data() == null ? Map.of() : wire.data();
        return new EventEnvelope(
                wire.id(), new TenantId(wire.tenant_id()), wire.type(), wire.type(), Instant.parse(wire.occurred_at()), data);
    }

    private record Wire(UUID id, String type, UUID tenant_id, String occurred_at, int schema_version, Map<String, Object> data) {}
}

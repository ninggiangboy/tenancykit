package dev.ngb.core.messaging.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The envelope survives a round trip unchanged, and a reader rejects a schema version it does not know. */
class EnvelopeCodecTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(), new TenantId(UUID.randomUUID()), "invoice.created",
                "invoice/42", Instant.parse("2026-11-12T07:00:00.123456Z"), Map.of("n", 1));
    }

    @Test
    void anEncodedEventDecodesWithTheSameFields() {
        EventEnvelope event = event();

        EventEnvelope decoded = new EnvelopeCodec(json).decode(new EnvelopeCodec(json).encode(event));

        assertThat(decoded.eventId()).isEqualTo(event.eventId());
        assertThat(decoded.tenantId()).isEqualTo(event.tenantId());
        assertThat(decoded.type()).isEqualTo(event.type());
        assertThat(decoded.aggregate()).as("the aggregate is carried on the wire").isEqualTo(event.aggregate());
        assertThat(decoded.aggregate()).isNotEqualTo(decoded.type());
        assertThat(decoded.occurredAt()).isEqualTo(event.occurredAt());
        assertThat(decoded.payload()).isEqualTo(event.payload());
    }

    @Test
    void aConsumerRejectsAnotherSchemaVersion() {
        byte[] v2 = new EnvelopeCodec(json, 2).encode(event());

        assertThatThrownBy(() -> new EnvelopeCodec(json, 1).decode(v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void theDocumentCarriesTheCamelCaseCanonicalFields() {
        JsonNode document = json.readValue(new EnvelopeCodec(json).encode(event()), JsonNode.class);

        assertThat(keys(document))
                .containsExactlyInAnyOrder("id", "type", "aggregate", "tenantId", "occurredAt", "schemaVersion", "data");
        assertThat(document.get("schemaVersion").asInt()).isEqualTo(EventDocument.SCHEMA_VERSION);
    }

    private static Set<String> keys(JsonNode node) {
        return new LinkedHashSet<>(node.propertyNames());
    }
}

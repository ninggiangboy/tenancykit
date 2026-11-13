package dev.ngb.billing.webhook.adapter.delivery.json;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.broker.EventDocument;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The webhook body is the one canonical envelope, byte for byte what the broker sends on AMQP. */
class JacksonEventBodyWriterTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theWebhookDocumentAndTheAmqpDocumentAreEqual() {
        DomainEvent event = new TestEvent(UUID.randomUUID(), new TenantId(UUID.randomUUID()), "charge.succeeded",
                "charge/42", Instant.parse("2026-11-10T09:30:00.123456Z"), Map.of("chargeId", UUID.randomUUID().toString()));

        String webhook = new JacksonEventBodyWriter(json).write(event);
        String amqp = new EventDocument(json).write(event);

        assertThat(webhook).isEqualTo(amqp);
        assertThat(keys(json.readTree(webhook)))
                .containsExactlyInAnyOrder("id", "type", "aggregate", "tenantId", "occurredAt", "schemaVersion", "data");
    }

    private static Set<String> keys(JsonNode node) {
        return new LinkedHashSet<>(node.propertyNames());
    }

    private record TestEvent(UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt, Map<String, Object> payload)
            implements DomainEvent {}
}

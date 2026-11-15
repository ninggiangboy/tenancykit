package dev.ngb.mailer.email.adapter.delivery.messaging;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.util.Text;
import dev.ngb.mailer.email.application.email.port.DeliveryResult;
import dev.ngb.mailer.email.application.email.port.DeliveryResultPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the delivery result to the transactional outbox, in the caller's transaction. The adapter names the wire
 * shape; the use case names only the fact.
 *
 * <p>Null optional fields are omitted rather than written as JSON {@code null}: the schema's
 * {@code additionalProperties: false} and its typed properties make a present-but-null key invalid, and a consumer
 * should not have to decide what a null {@code providerMessageId} means.
 */
public class OutboxDeliveryResultPublisher implements DeliveryResultPublisher {

    /** The suite event type; the routing key on {@code mailer.events} and the schema file name. */
    public static final String TYPE = "email-delivery.changed";

    private static final String AGGREGATE = "email-delivery/";
    private static final int MAX_ERROR = 500;

    private final EventPublisher outbox;
    private final IdGenerator ids;

    public OutboxDeliveryResultPublisher(EventPublisher outbox, IdGenerator ids) {
        this.outbox = outbox;
        this.ids = ids;
    }

    @Override
    public void publish(DeliveryResult result) {
        outbox.publish(new DeliveryResultEvent(
                ids.newId(),
                result.tenantId(),
                TYPE,
                AGGREGATE + result.deliveryId(),
                result.completedAt(),
                payload(result)));
    }

    private static Map<String, Object> payload(DeliveryResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", result.requestId());
        data.put("recipient", result.recipient());
        data.put("status", result.status().name());
        data.put("attempts", result.attempts());
        data.put("completedAt", result.completedAt().toString());
        if (result.providerMessageId() != null && !result.providerMessageId().isBlank()) {
            data.put("providerMessageId", result.providerMessageId());
        }
        if (result.lastError() != null && !result.lastError().isBlank()) {
            data.put("lastError", Text.clip(result.lastError(), MAX_ERROR));
        }
        return Map.copyOf(data);
    }

    private record DeliveryResultEvent(
            UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt, Map<String, Object> payload)
            implements DomainEvent {}
}

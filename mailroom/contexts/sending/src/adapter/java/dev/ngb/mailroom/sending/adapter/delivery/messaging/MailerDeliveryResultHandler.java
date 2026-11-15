package dev.ngb.mailroom.sending.adapter.delivery.messaging;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.mailroom.sending.application.email.command.ApplyDeliveryResultUseCase;
import dev.ngb.mailroom.sending.domain.email.DeliveryOutcome;
import java.time.Instant;
import java.util.Map;

/**
 * Consumes mailer's {@code email-delivery.changed}. The dispatcher has already bound the tenant and deduplicated the
 * event; this handler only translates the wire shape and hands it to the use case.
 */
public class MailerDeliveryResultHandler implements InboundEventHandler {

    public static final String CONSUMER = "email-mailer-events";
    public static final String TYPE = "email-delivery.changed";

    private final ApplyDeliveryResultUseCase apply;

    public MailerDeliveryResultHandler(ApplyDeliveryResultUseCase apply) {
        this.apply = apply;
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean accepts(String type) {
        return TYPE.equals(type);
    }

    @Override
    public void handle(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        DeliveryOutcome outcome = new DeliveryOutcome(
                String.valueOf(payload.get("requestId")),
                String.valueOf(payload.get("status")),
                payload.get("attempts") instanceof Number attempts ? attempts.intValue() : 0,
                Instant.parse(String.valueOf(payload.get("completedAt"))),
                payload.get("providerMessageId") == null ? null : String.valueOf(payload.get("providerMessageId")),
                payload.get("lastError") == null ? null : String.valueOf(payload.get("lastError")));
        apply.execute(outcome);
    }
}

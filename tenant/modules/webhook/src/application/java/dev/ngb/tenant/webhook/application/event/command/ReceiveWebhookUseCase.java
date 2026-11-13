package dev.ngb.tenant.webhook.application.event.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.tenant.webhook.api.event.WebhookBodyParser;
import dev.ngb.tenant.webhook.api.event.WebhookDecision;
import dev.ngb.tenant.webhook.api.event.WebhookEnvelope;
import dev.ngb.tenant.webhook.application.event.port.WebhookRepository;
import dev.ngb.tenant.webhook.application.event.service.WebhookPolicy;
import dev.ngb.tenant.webhook.domain.event.WebhookEvent;
import dev.ngb.tenant.webhook.domain.event.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies, deduplicates and applies one delivery. The signature is checked over the raw bytes before the body
 * is handed to the parser, a duplicate answers without repeating the policy, and the decision commits as one
 * unit of work: an event is never recorded as received without its outcome.
 */
public class ReceiveWebhookUseCase {

    public record Headers(String eventId, String eventType, String timestamp, String signature) {}

    public record Outcome(boolean duplicate, WebhookDecision decision, WebhookEnvelope envelope) {}

    private final WebhookRepository events;
    private final WebhookPolicy policy;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final IdGenerator ids;
    private final String signingSecret;
    private final Duration tolerance;

    public ReceiveWebhookUseCase(
            WebhookRepository events,
            WebhookPolicy policy,
            UnitOfWork unitOfWork,
            Clock clock,
            IdGenerator ids,
            String signingSecret,
            Duration tolerance) {
        this.events = events;
        this.policy = policy;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.ids = ids;
        this.signingSecret = signingSecret;
        this.tolerance = tolerance;
    }

    public Outcome receive(Headers headers, byte[] rawBody, WebhookBodyParser parser) {
        verify(headers, rawBody);
        WebhookEnvelope envelope = parser.parse(rawBody);
        if (!envelope.id().equals(headers.eventId())) {
            throw new UnauthorizedException("the delivery signature could not be verified");
        }
        if (headers.eventType() != null && !headers.eventType().equals(envelope.type())) {
            throw new UnauthorizedException("the event type header does not match the body");
        }
        String payload = new String(rawBody, StandardCharsets.UTF_8);
        try {
            return unitOfWork.transactional(() -> apply(envelope, payload));
        } catch (RuntimeException e) {
            record(envelope, payload, e);
            throw e;
        }
    }

    private void verify(Headers headers, byte[] rawBody) {
        boolean valid = WebhookSignature.verify(
                signingSecret,
                headers.timestamp(),
                rawBody,
                headers.signature(),
                clock.now().getEpochSecond(),
                tolerance.toSeconds());
        if (!valid) {
            throw new UnauthorizedException("the delivery signature could not be verified");
        }
    }

    private Outcome apply(WebhookEnvelope envelope, String payload) {
        WebhookEvent event = WebhookEvent.received(
                ids, envelope.id(), envelope.type(), envelope.tenantId(), envelope.occurredAt(), payload, clock.now());
        if (!events.saveIfAbsent(event)) {
            return new Outcome(true, WebhookDecision.CONTINUE, envelope);
        }
        WebhookDecision decision = policy.apply(envelope);
        events.save(event.processed(decision));
        return new Outcome(false, decision, envelope);
    }

    private void record(WebhookEnvelope envelope, String payload, RuntimeException cause) {
        WebhookEvent failed = WebhookEvent.received(
                        ids,
                        envelope.id(),
                        envelope.type(),
                        envelope.tenantId(),
                        envelope.occurredAt(),
                        payload,
                        clock.now())
                .failed(message(cause));
        try {
            unitOfWork.transactional(() -> {
                events.saveIfAbsent(failed);
                return null;
            });
        } catch (RuntimeException failure) {
            cause.addSuppressed(failure);
        }
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}

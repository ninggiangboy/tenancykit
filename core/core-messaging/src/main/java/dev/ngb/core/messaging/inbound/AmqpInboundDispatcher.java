package dev.ngb.core.messaging.inbound;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.messaging.broker.EnvelopeCodec;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.retry.support.RetryTemplate;

/**
 * Turns one AMQP message into work: decodes the envelope, binds the tenant scope it carries, opens a unit of work,
 * deduplicates through the inbox and calls the handlers that accept the type. A handler failure is retried a few
 * times with backoff; when the attempts are spent the message is rejected without requeue, so it lands in the
 * consumer's dead-letter queue instead of looping forever.
 */
public class AmqpInboundDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpInboundDispatcher.class);

    private final EnvelopeCodec codec;
    private final List<InboundEventHandler> handlers;
    private final EventInbox inbox;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final RetryTemplate retry;

    public AmqpInboundDispatcher(
            EnvelopeCodec codec, List<InboundEventHandler> handlers, EventInbox inbox, UnitOfWork unitOfWork, TenantContext tenantContext,
            RetryTemplate retry) {
        this.codec = codec;
        this.handlers = List.copyOf(handlers);
        this.inbox = inbox;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.retry = retry;
    }

    /** Handles one delivery. Throwing {@link AmqpRejectAndDontRequeueException} is what routes it to the DLQ. */
    public void onMessage(byte[] body) {
        EventEnvelope event = codec.decode(body);
        List<InboundEventHandler> accepting = handlers.stream().filter(handler -> handler.accepts(event.type())).toList();
        if (accepting.isEmpty()) {
            LOG.debug("ignoring event {} of unsubscribed type {}", event.eventId(), event.type());
            return;
        }
        RequestScope scope = RequestScope.PLATFORM_TENANT_ID.equals(event.tenantId())
                ? RequestScope.platform()
                : RequestScope.tenant(event.tenantId());
        try {
            tenantContext.callAs(scope, () -> retry.execute(status -> apply(accepting, event)));
        } catch (RuntimeException failure) {
            LOG.error("event {} ({}) exhausted its retries", event.eventId(), event.type(), failure);
            throw new AmqpRejectAndDontRequeueException("event " + event.eventId() + " exhausted its retries", failure);
        }
    }

    private boolean apply(List<InboundEventHandler> accepting, EventEnvelope event) {
        return unitOfWork.transactional(() -> {
            for (InboundEventHandler handler : accepting) {
                if (inbox.firstTime(handler.consumer(), event)) {
                    handler.handle(event);
                }
            }
            return true;
        });
    }

    /** A retry template with three attempts and exponential backoff, as the suite's consumers use. */
    public static RetryTemplate defaultRetry() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(100, 2, 2_000)
                .retryOn(RuntimeException.class)
                .build();
    }
}

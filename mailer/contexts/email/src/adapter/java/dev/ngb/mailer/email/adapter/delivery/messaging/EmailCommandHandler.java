package dev.ngb.mailer.email.adapter.delivery.messaging;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.messaging.inbound.InboundEventHandler;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one {@code email.send} command into validated ledger rows.
 *
 * <p>It is network-free on purpose. It runs inside the dispatcher's transaction, so calling a provider here would hold
 * a database transaction open across a network call and would re-send on every retry; the delivery job is what sends.
 *
 * <p>The consumer name is asserted in a test: two handlers sharing one would make the inbox drop one of them.
 */
public class EmailCommandHandler implements InboundEventHandler {

    /** The inbox key. One consumer name per handler, so two handlers never share a deduplication row. */
    public static final String CONSUMER = "mailer.email-send";

    /** The one command type this exchange carries today. */
    public static final String TYPE = "email.send";

    private static final Logger LOG = LoggerFactory.getLogger(EmailCommandHandler.class);

    private final CommandSchemaValidator validator;
    private final AcceptEmailCommandUseCase useCase;

    public EmailCommandHandler(CommandSchemaValidator validator, AcceptEmailCommandUseCase useCase) {
        this.validator = validator;
        this.useCase = useCase;
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
        if (RequestScope.PLATFORM_TENANT_ID.equals(event.tenantId())) {
            // Mailer has no legitimate platform-scope send, so a command naming the platform tenant is a mistake or an
            // attempt to act outside any tenant. Refusing it keeps the trust boundary narrow.
            throw new InvalidEmailCommandException("email.send is not accepted for the platform tenant");
        }
        AcceptEmailCommandUseCase.Command command = validator.validate(event);
        AcceptEmailCommandUseCase.Outcome outcome = useCase.accept(event.tenantId(), command);
        // The request id and the recipient count, never the body: a body is personal data.
        if (outcome == AcceptEmailCommandUseCase.Outcome.DUPLICATE) {
            LOG.info("email.send {} was already accepted; nothing to do", command.requestId());
        } else if (outcome == AcceptEmailCommandUseCase.Outcome.RESURRECTED) {
            LOG.warn("email.send {} resurrected a dead delivery; it will be retried", command.requestId());
        } else {
            LOG.info("email.send {} accepted for {} recipient(s)", command.requestId(), command.to().size());
        }
    }
}

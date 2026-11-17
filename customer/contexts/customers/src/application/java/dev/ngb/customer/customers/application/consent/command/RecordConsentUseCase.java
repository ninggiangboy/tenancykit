package dev.ngb.customer.customers.application.consent.command;

import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.application.consent.exception.UnknownConsentTopicException;
import dev.ngb.customer.customers.application.consent.port.ConsentEventRepository;
import dev.ngb.customer.customers.application.consent.port.ConsentTopicRepository;
import dev.ngb.customer.customers.application.consent.service.ConsentRules;
import dev.ngb.customer.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.customer.customers.application.customer.port.CustomerRepository;
import dev.ngb.customer.customers.application.customer.service.CustomerRules;
import dev.ngb.customer.customers.domain.consent.ConsentEvent;
import dev.ngb.customer.customers.domain.consent.ConsentStatus;
import dev.ngb.customer.customers.domain.customer.Customer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code POST /customers/{externalRef}/consents}: one consent record for one topic, appended.
 *
 * <p>Nothing is ever rewritten: a revocation is a record of its own and the grant it revokes stays in the history. The
 * caller's optional reference makes the call replayable — the same reference with the same body returns the record that
 * was stored, and with another body it is a conflict, because a reference that means one agreement cannot name two.
 */
public class RecordConsentUseCase {

    /** One record as the Tenant sent it. */
    public record Command(
            String topicKey, String status, Instant occurredAt, String source, String evidenceRef, String consentRef) {}

    /**
     * @param event the record now stored for this call
     * @param created whether this call appended it, rather than replaying the one an earlier call appended
     */
    public record Result(ConsentEvent event, boolean created) {}

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final ConsentTopicRepository topics;
    private final ConsentEventRepository events;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration maxFutureSkew;

    public RecordConsentUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            ConsentTopicRepository topics,
            ConsentEventRepository events,
            IdGenerator ids,
            Clock clock,
            Duration maxFutureSkew) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.topics = topics;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.maxFutureSkew = maxFutureSkew;
    }

    public Result execute(String externalRef, Command command) {
        String ref = CustomerRules.externalRef(externalRef);
        String topicKey = ConsentRules.key(command.topicKey());
        ConsentStatus status = ConsentRules.status(command.status());
        String source = ConsentRules.source(command.source());
        String evidenceRef = ConsentRules.evidenceRef(command.evidenceRef());
        String consentRef = ConsentRules.consentRef(command.consentRef());
        Instant now = clock.now();
        Instant occurredAt = ConsentRules.occurredAt(command.occurredAt(), now, maxFutureSkew);
        return unitOfWork.transactional(() -> {
            Customer customer = customers
                    .lockByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            if (customer.isErased()) {
                throw new CustomerErasedException();
            }
            topics.findByKey(topicKey)
                    .filter(topic -> topic.acceptsRecords())
                    .orElseThrow(UnknownConsentTopicException::new);
            if (consentRef != null) {
                Optional<ConsentEvent> seen = events.findByConsentRef(customer.id(), consentRef);
                if (seen.isPresent()) {
                    return replay(seen.get(), topicKey, status, occurredAt, source, evidenceRef, consentRef);
                }
            }
            ConsentEvent event = ConsentEvent.record(
                    ids.newId(),
                    customer.tenantId(),
                    customer.id(),
                    topicKey,
                    status,
                    occurredAt,
                    now,
                    source,
                    evidenceRef,
                    consentRef);
            if (events.insertIfAbsent(event)) {
                return new Result(event, true);
            }
            ConsentEvent winner = events
                    .findByConsentRef(customer.id(), consentRef)
                    .orElseThrow(() -> new IllegalStateException(
                            "the record that won the insert of this reference is not readable"));
            return replay(winner, topicKey, status, occurredAt, source, evidenceRef, consentRef);
        });
    }

    private static Result replay(
            ConsentEvent stored,
            String topicKey,
            ConsentStatus status,
            Instant occurredAt,
            String source,
            String evidenceRef,
            String consentRef) {
        if (!stored.sameRequest(topicKey, status, occurredAt, source, evidenceRef)) {
            throw new IdempotencyConflictException("consent", consentRef);
        }
        return new Result(stored, false);
    }
}

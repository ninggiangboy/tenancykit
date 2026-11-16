package dev.ngb.crm.customers.application.customer.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.api.customer.CustomerErasureParticipant;
import dev.ngb.crm.customers.application.consent.port.ConsentEventRepository;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.port.ErasureRequestRepository;
import dev.ngb.crm.customers.application.customer.service.CustomerEventPublisher;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;
import dev.ngb.crm.customers.application.customer.service.ErasureRefHash;
import dev.ngb.crm.customers.domain.customer.Customer;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import dev.ngb.crm.customers.domain.customer.ErasureRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code POST /customers/{externalRef}/erasure}: one call cuts every tie this tenant's crm holds to a person, and a
 * retry of that call answers the same thing instead of erasing again.
 *
 * <p>In one transaction, under the customer's lock: the attributes, the field values and the tags are cleared, the
 * consent records lose the free text the Tenant filled and the activities lose their payloads and references through
 * the contexts that own those rows, the customer becomes the anonymous marker {@code erased:<id>}, the record of the
 * request is appended and {@code customer.erased} goes to the outbox. Everything that pointed at the customer — its
 * orders, its consent history, its typed and timed activities — stays as an anonymous business record.
 *
 * <p>A retry is answered from the record of the request, which is keyed by the hash of the reference rather than the
 * reference: the reference the caller sent is free again and may already name another customer, so a retried call must
 * find its own record before it looks at any customer. Only a reference this tenant never used, for a customer it does
 * not hold, is a {@code 404}.
 */
public class EraseCustomerUseCase {

    /** Whether this call erased the person or replayed an erasure it performed before. */
    public enum Outcome {
        ERASED,
        REPLAYED
    }

    public record Result(CustomerId customerId, Instant erasedAt, Outcome outcome) {}

    private static final String UNKNOWN_CUSTOMER = "No customer exists here with that reference.";

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final ErasureRequestRepository requests;
    private final ConsentEventRepository consents;
    private final List<CustomerErasureParticipant> participants;
    private final CustomerEventPublisher events;
    private final TenantContext tenantContext;
    private final Clock clock;

    public EraseCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            ErasureRequestRepository requests,
            ConsentEventRepository consents,
            List<CustomerErasureParticipant> participants,
            CustomerEventPublisher events,
            TenantContext tenantContext,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.requests = requests;
        this.consents = consents;
        this.participants = List.copyOf(participants);
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public Result execute(String externalRef) {
        String ref = CustomerRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            String refHash = ErasureRefHash.of(tenantId, ref);
            Optional<ErasureRequest> performed = requests.findByRefHash(refHash);
            if (performed.isPresent()) {
                return replay(performed.get());
            }
            // The customer is locked before anything about it changes, so two calls that do mean to erase the same
            // person run one after the other rather than over each other.
            Optional<Customer> locked = customers.lockByExternalRef(ref);
            if (locked.isEmpty()) {
                // A concurrent retry of the same call erased the customer while this one waited for the lock.
                return replay(requests
                        .findByRefHash(refHash)
                        .orElseThrow(() -> new NotFoundException(UNKNOWN_CUSTOMER)));
            }
            Customer customer = locked.get();
            Instant now = clock.now();
            if (!customer.erase(now)) {
                // The reference names the marker an erasure left, so the person is already anonymous: nothing to write
                // and nothing to announce a second time.
                return new Result(customer.id(), customer.updatedAt(), Outcome.REPLAYED);
            }
            customers.erase(customer);
            consents.clearPersonalData(customer.id());
            participants.forEach(participant -> participant.erase(customer.id().value()));
            requests.insertIfAbsent(new ErasureRequest(tenantId, refHash, customer.id(), now));
            events.erased(customer);
            return new Result(customer.id(), now, Outcome.ERASED);
        });
    }

    private static Result replay(ErasureRequest performed) {
        return new Result(performed.customerId(), performed.erasedAt(), Outcome.REPLAYED);
    }
}

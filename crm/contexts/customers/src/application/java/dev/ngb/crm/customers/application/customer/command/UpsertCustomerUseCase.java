package dev.ngb.crm.customers.application.customer.command;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;
import dev.ngb.crm.customers.domain.customer.Customer;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code PUT /customers/{externalRef}}: the Tenant's system states what a customer is, and crm stores exactly that.
 *
 * <p>The reference is the natural key, so the call is safe to repeat. A body identical to what is stored changes
 * nothing, bumps no version and announces nothing; a body that differs replaces the attributes and bumps the version,
 * which is the ETag the caller sends back in {@code If-Match}. Only a creation is announced, and the event is appended
 * to the outbox in the same transaction as the row.
 */
public class UpsertCustomerUseCase {

    /** What the call did: created the customer, replaced its attributes, or found it already identical. */
    public enum Outcome {
        CREATED,
        UPDATED,
        UNCHANGED
    }

    /** One customer as the Tenant sent it. A null attribute is one the Tenant does not have. */
    public record Command(String externalRef, String displayName, String phone, String email) {}

    public record Result(Customer customer, Outcome outcome) {

        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public UpsertCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String externalRef = CustomerRules.externalRef(command.externalRef());
        String displayName = CustomerRules.displayName(command.displayName());
        String phone = CustomerRules.phone(command.phone());
        String phoneDigits = CustomerRules.phoneDigits(phone);
        String email = CustomerRules.email(command.email());
        return unitOfWork.transactional(
                () -> upsert(externalRef, displayName, phone, phoneDigits, email));
    }

    /**
     * The one path the single call and every batch item take: change the customer if it exists, otherwise insert it
     * and announce it. The insert can lose a race with a concurrent first-time upsert of the same reference; the loss
     * is not an error, because the row that won is the customer this call meant to write.
     */
    private Result upsert(
            String externalRef, String displayName, String phone, String phoneDigits, String email) {
        Instant now = clock.now();
        Optional<Customer> existing = customers.lockByExternalRef(externalRef);
        if (existing.isPresent()) {
            return replace(existing.get(), displayName, phone, phoneDigits, email, now);
        }
        Customer created = Customer.create(
                new CustomerId(ids.newId()),
                tenantContext.requireTenant(),
                externalRef,
                displayName,
                phone,
                phoneDigits,
                email,
                now);
        if (customers.insertIfAbsent(created)) {
            events.publish(created.created(ids.newId()));
            return new Result(created, Outcome.CREATED);
        }
        Customer winner = customers
                .lockByExternalRef(externalRef)
                .orElseThrow(() -> new IllegalStateException(
                        "the customer that won the insert of this reference is not readable"));
        return replace(winner, displayName, phone, phoneDigits, email, now);
    }

    private Result replace(
            Customer customer, String displayName, String phone, String phoneDigits, String email, Instant now) {
        if (customer.isErased()) {
            throw new CustomerErasedException();
        }
        boolean changed = customer.replace(displayName, phone, phoneDigits, email, now);
        if (changed) {
            customers.update(customer);
        }
        return new Result(customer, changed ? Outcome.UPDATED : Outcome.UNCHANGED);
    }
}

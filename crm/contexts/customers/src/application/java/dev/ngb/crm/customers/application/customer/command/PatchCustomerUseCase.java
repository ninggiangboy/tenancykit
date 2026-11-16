package dev.ngb.crm.customers.application.customer.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.crm.customers.application.customer.exception.CustomerErasedException;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;
import dev.ngb.crm.customers.domain.customer.Customer;

/**
 * {@code PATCH /customers/{externalRef}}: only the properties the caller sent change, and only if the customer is
 * still the version the caller read. A null property means "leave this one alone", which is why clearing an attribute
 * is a {@code PUT} with a null body value rather than a patch.
 */
public class PatchCustomerUseCase {

    /** A change request: a null field is left as it is. */
    public record Change(String displayName, String phone, String email) {}

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final Clock clock;

    public PatchCustomerUseCase(UnitOfWork unitOfWork, CustomerRepository customers, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.clock = clock;
    }

    public Customer execute(String externalRef, Change change, ExpectedVersion expected) {
        String ref = CustomerRules.externalRef(externalRef);
        // A patch that changes nothing is not an error, but it is also not a change: no version bump, no announcement.
        String displayName = change.displayName() == null ? null : CustomerRules.displayName(change.displayName());
        String phone = change.phone() == null ? null : CustomerRules.phone(change.phone());
        String email = change.email() == null ? null : CustomerRules.email(change.email());
        return unitOfWork.transactional(() -> {
            Customer customer = customers
                    .lockByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            if (customer.isErased()) {
                throw new CustomerErasedException();
            }
            expected.check(customer.version());
            String newPhone = phone == null ? customer.phone() : phone;
            String phoneDigits = phone == null ? customer.phoneDigits() : CustomerRules.phoneDigits(newPhone);
            boolean changed = customer.replace(
                    displayName == null ? customer.displayName() : displayName,
                    newPhone,
                    phoneDigits,
                    email == null ? customer.email() : email,
                    clock.now());
            if (changed) {
                customers.update(customer);
            }
            return customer;
        });
    }
}

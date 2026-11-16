package dev.ngb.crm.customers.adapter.customer.persistence;

import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.customers.api.customer.CustomerRef;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.domain.customer.Customer;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer seam another context calls: it resolves a reference through the customers context's own repository and
 * creates a missing customer through the one upsert every other path uses.
 *
 * <p>Taking the upsert rather than inserting a row here is what keeps the two ways of creating a customer identical:
 * the same rules trim the reference and the same code writes {@code customer.created} to the outbox inside the
 * caller's transaction. A reference the Tenant already used is never rewritten — an order that mentions a customer
 * states nothing about it, so the stored customer is returned as it is.
 */
public class JooqCustomerDirectory implements CustomerDirectory {

    private final CustomerRepository customers;
    private final UpsertCustomerUseCase upsert;

    public JooqCustomerDirectory(CustomerRepository customers, UpsertCustomerUseCase upsert) {
        this.customers = customers;
        this.upsert = upsert;
    }

    @Override
    public Optional<CustomerRef> findByExternalRef(String externalRef) {
        return customers.findByExternalRef(externalRef).map(JooqCustomerDirectory::ref);
    }

    @Override
    public Optional<CustomerRef> findByCustomerId(UUID customerId) {
        return customers.findById(customerId).map(JooqCustomerDirectory::ref);
    }

    @Override
    public Optional<CustomerRef> createIfAbsent(String externalRef) {
        Optional<Customer> existing = customers.findByExternalRef(externalRef);
        if (existing.isPresent()) {
            return existing.filter(customer -> !customer.isErased()).map(JooqCustomerDirectory::ref);
        }
        UpsertCustomerUseCase.Result created = upsert.execute(new UpsertCustomerUseCase.Command(
                externalRef, null, null, null, null, null));
        return Optional.of(ref(created.customer()));
    }

    private static CustomerRef ref(Customer customer) {
        return new CustomerRef(customer.id().value(), customer.externalRef(), customer.isErased());
    }
}

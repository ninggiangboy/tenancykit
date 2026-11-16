package dev.ngb.crm.customers.application.customer.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.customers.application.customer.exception.InvalidCustomerException;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;

/** Reads the current tenant's customers for the GET handlers. */
public class CustomerQueries {

    private final UnitOfWork unitOfWork;
    private final CustomerReadRepository customers;

    public CustomerQueries(UnitOfWork unitOfWork, CustomerReadRepository customers) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
    }

    public Page<CustomerView> list(ListQuery query) {
        return unitOfWork.transactional(() -> customers.list(query));
    }

    /** @throws InvalidCustomerException when the path is not a usable reference */
    public CustomerView get(String externalRef) {
        String ref = CustomerRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> customers
                .findByExternalRef(ref)
                .orElseThrow(() -> new NotFoundException("No customer exists here with that reference.")));
    }
}

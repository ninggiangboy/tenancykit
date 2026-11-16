package dev.ngb.billing.customer.application.customer.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.customer.application.customer.port.CustomerRepository;
import dev.ngb.billing.customer.application.customer.service.TenantSystems;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.billing.sharedkernel.id.CustomerId;

/** Read access to the current tenant's customers. */
public class CustomerQueries {

    private final UnitOfWork unitOfWork;
    private final CustomerReadRepository customers;

    public CustomerQueries(UnitOfWork unitOfWork, CustomerReadRepository customers) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
    }

    public Page<CustomerView> list(ListQuery query) {
        return list(query, null);
    }

    /** A Tenant System sees only the customers mapped to it; any other caller sees the whole tenant. */
    public Page<CustomerView> list(ListQuery query, TenantSystemId caller) {
        return unitOfWork.transactional(() -> customers.list(query, caller == null ? null : caller.value()));
    }

    public CustomerView get(CustomerId id) {
        return get(id, null);
    }

    public CustomerView get(CustomerId id, TenantSystemId caller) {
        return unitOfWork.transactional(() -> (caller == null ? customers.findById(id) : customers.findByIdMappedTo(id, caller.value()))
                .orElseThrow(() -> new NotFoundException("customer not found")));
    }

    /**
     * A Tenant System looks up only its own mapping and may not name another one; a Tenant User names the system
     * and must name one.
     */
    public CustomerView getByExternalUser(TenantSystemId caller, String requestedTenantSystemId, String externalUserId) {
        TenantSystemId system = TenantSystems.actingFor(caller, requestedTenantSystemId);
        if (system == null) {
            throw new IllegalArgumentException("tenantSystemId is required");
        }
        return unitOfWork.transactional(() -> customers.findByExternalIdentity(system.value(), externalUserId)
                .orElseThrow(() -> new NotFoundException("customer not found")));
    }
}

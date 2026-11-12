package dev.ngb.billing.customer.application.customer.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.billing.customer.application.customer.port.CustomerRepository;
import dev.ngb.billing.customer.domain.customer.Customer;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.billing.sharedkernel.id.CustomerId;

/** Reading and maintaining the Customers of the current tenant. */
public class ManageCustomerUseCase {

    private final UnitOfWork unitOfWork;
    private final CustomerRepository customers;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final Clock clock;

    public ManageCustomerUseCase(
            UnitOfWork unitOfWork, CustomerRepository customers, AuditLog auditLog, TenantContext tenantContext, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public Customer update(Audit audit, CustomerId id, String displayName, String email, ExpectedVersion expected) {
        return update(audit, id, displayName, email, expected, null);
    }

    public Customer update(Audit audit, CustomerId id, String displayName, String email, ExpectedVersion expected, TenantSystemId caller) {
        return unitOfWork.transactional(() -> {
            Customer customer = requireVisible(id, caller);
            expected.check(customer.version());
            boolean changed;
            try {
                changed = customer.changeContact(displayName, email);
            } catch (IllegalStateException deactivated) {
                throw new ConflictException(deactivated.getMessage());
            }
            if (changed) {
                customers.update(customer);
                record(audit, "customer.updated", customer);
            }
            return customer;
        });
    }

    public Customer deactivate(Audit audit, CustomerId id, ExpectedVersion expected) {
        return deactivate(audit, id, expected, null);
    }

    public Customer deactivate(Audit audit, CustomerId id, ExpectedVersion expected, TenantSystemId caller) {
        return unitOfWork.transactional(() -> {
            Customer customer = requireVisible(id, caller);
            expected.check(customer.version());
            if (customer.deactivate()) {
                customers.update(customer);
                record(audit, "customer.deactivated", customer);
            }
            return customer;
        });
    }

    /**
     * A Tenant System may change only a customer it mapped; anything else is not found for it. Visibility is checked
     * before the lock, so the version of a customer it cannot see never leaks through a 412.
     */
    private Customer requireVisible(CustomerId id, TenantSystemId caller) {
        if (caller != null && !customers.isMappedTo(id, caller)) {
            throw new NotFoundException("customer not found");
        }
        return customers.lockById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private void record(Audit audit, String action, Customer customer) {
        auditLog.record(AuditEntry.tenant(
                tenantContext.requireTenant(), audit.actor(), action, "customer/" + customer.id(), audit.requestId(), clock.now()));
    }
}

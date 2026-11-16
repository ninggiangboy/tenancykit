package dev.ngb.billing.customer.application.customer.query;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's customers. */
public interface CustomerReadRepository {

    /** One page of customers; {@code tenantSystemId} narrows it to the system's mappings, and null reads the whole tenant. */
    Page<CustomerView> list(ListQuery query, UUID tenantSystemId);

    Optional<CustomerView> findById(CustomerId id);

    /** The customer only when it is mapped to the system, with only that system's identity in the view. */
    Optional<CustomerView> findByIdMappedTo(CustomerId id, UUID tenantSystemId);

    Optional<CustomerView> findByExternalIdentity(UUID tenantSystemId, String externalUserId);

    List<UUID> mappedCustomerIds(UUID tenantSystemId);
}

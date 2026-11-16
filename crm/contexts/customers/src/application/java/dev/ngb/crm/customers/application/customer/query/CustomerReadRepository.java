package dev.ngb.crm.customers.application.customer.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;

/**
 * Reads customers straight into the view; the aggregate is never rebuilt for a read.
 *
 * <p>The list contract's own filters are one call, and the search terms the customer list adds are another, so a
 * caller that only pages is not asked for the Tenant's field definitions or consent topics.
 */
public interface CustomerReadRepository {

    Page<CustomerView> list(ListQuery query);

    /** The list contract's paging and ordering, narrowed by the search terms the customer list answers. */
    Page<CustomerView> search(ListQuery query, CustomerSearchCriteria criteria);

    Optional<CustomerView> findByExternalRef(String externalRef);
}

package dev.ngb.crm.customers.application.customer.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;

/** Reads customers straight into the view; the aggregate is never rebuilt for a read. */
public interface CustomerReadRepository {

    Page<CustomerView> list(ListQuery query);

    Optional<CustomerView> findByExternalRef(String externalRef);
}

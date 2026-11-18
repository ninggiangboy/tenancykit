package dev.ngb.notification.dispatch.application.notification.query;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** The read side of the dispatch ledger, filtered, sorted and paged by the list-query contract. */
public interface DispatchReadRepository {

    Page<DispatchView> list(ListQuery query, TenantId tenantId);
}

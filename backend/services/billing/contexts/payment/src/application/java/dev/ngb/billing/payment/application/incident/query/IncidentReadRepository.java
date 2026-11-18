package dev.ngb.billing.payment.application.incident.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** Read-only projection of incidents. */
public interface IncidentReadRepository {

    Page<IncidentView> list(ListQuery query);
}

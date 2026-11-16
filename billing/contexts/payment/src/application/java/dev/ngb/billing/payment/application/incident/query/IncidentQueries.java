package dev.ngb.billing.payment.application.incident.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;

/** Reads incidents for the GET handler. */
public class IncidentQueries {


    private final UnitOfWork unitOfWork;
    private final IncidentReadRepository incidents;

    public IncidentQueries(UnitOfWork unitOfWork, IncidentReadRepository incidents) {
        this.unitOfWork = unitOfWork;
        this.incidents = incidents;
    }

    public Page<IncidentView> list(ListQuery query) {
        return unitOfWork.transactional(() -> incidents.list(query));
    }
}

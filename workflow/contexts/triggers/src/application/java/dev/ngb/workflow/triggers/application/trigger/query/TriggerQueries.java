package dev.ngb.workflow.triggers.application.trigger.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Reads the current tenant's triggers for the management GET handlers. */
public class TriggerQueries {

    private final UnitOfWork unitOfWork;
    private final TriggerReadRepository triggers;

    public TriggerQueries(UnitOfWork unitOfWork, TriggerReadRepository triggers) {
        this.unitOfWork = unitOfWork;
        this.triggers = triggers;
    }

    public Page<TriggerView> list(ListQuery query) {
        return unitOfWork.transactional(() -> triggers.list(query));
    }

    public TriggerView get(UUID id) {
        return unitOfWork.transactional(() -> triggers
                .findById(id)
                .orElseThrow(() -> new NotFoundException("No workflow trigger exists here.")));
    }
}

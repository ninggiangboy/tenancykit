package dev.ngb.workflow.triggers.application.trigger.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's triggers. */
public interface TriggerReadRepository {

    Page<TriggerView> list(ListQuery query);

    Optional<TriggerView> findById(UUID id);
}

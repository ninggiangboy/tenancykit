package dev.ngb.attribution.attribution.application.goal.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The goal read side: a page of a tenant's goals and one by id. */
public interface GoalReadRepository {

    /** @return one page of the goals the filter admits, in the order the query names */
    Page<GoalView> list(ListQuery query);

    Optional<GoalView> find(UUID goalId);
}

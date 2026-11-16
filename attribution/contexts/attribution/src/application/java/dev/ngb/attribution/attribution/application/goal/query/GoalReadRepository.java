package dev.ngb.attribution.attribution.application.goal.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The goal read side: a tenant's goals and one by id. */
public interface GoalReadRepository {

    List<GoalView> list();

    Optional<GoalView> find(UUID goalId);
}

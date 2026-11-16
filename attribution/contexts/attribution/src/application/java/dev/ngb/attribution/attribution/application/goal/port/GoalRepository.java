package dev.ngb.attribution.attribution.application.goal.port;

import dev.ngb.attribution.attribution.domain.goal.Goal;
import java.util.Optional;
import java.util.UUID;

/** The write side of goals. */
public interface GoalRepository {

    void insert(Goal goal);

    Optional<Goal> findById(UUID goalId);

    Optional<Goal> findByKey(String key);

    boolean update(Goal goal, long expectedVersion);

    boolean keyTaken(String key, UUID exceptId);
}

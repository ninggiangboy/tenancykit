package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The instance row: created once, variables replaced by a step, finished once. */
public interface InstanceRepository {

    void add(Instance instance);

    Optional<Instance> findById(UUID id);

    /** Replaces the variables under the optimistic version and returns the version the row now holds. */
    long updateVariables(UUID id, Map<String, Object> variables, long expectedVersion);

    /** Moves the instance to a terminal status under the optimistic version. */
    void finish(UUID id, InstanceStatus status, Instant finishedAt, long expectedVersion);
}

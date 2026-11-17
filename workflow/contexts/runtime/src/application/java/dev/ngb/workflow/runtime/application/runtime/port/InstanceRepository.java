package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The instance row: created once, variables replaced by a step, moved to WAITING, finished once. */
public interface InstanceRepository {

    void add(Instance instance);

    Optional<Instance> findById(UUID id);

    /** The instance a repeated caller already created under its idempotency key, if any. */
    Optional<Instance> findByIdempotencyKey(String key);

    /** Replaces the variables under the optimistic version and returns the version the row now holds. */
    long updateVariables(UUID id, Map<String, Object> variables, long expectedVersion);

    /** Moves a running instance to a non-terminal status such as WAITING, under the optimistic version. */
    long setStatus(UUID id, InstanceStatus status, long expectedVersion);

    /** Moves the instance to a terminal status under the optimistic version. */
    void finish(UUID id, InstanceStatus status, Instant finishedAt, long expectedVersion);
}

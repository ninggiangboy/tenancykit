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

    /**
     * Replaces the variables only when the row still holds the expected version, and answers whether it did. Parallel
     * branches may write at the same instant; the engine uses this to retry rather than fail a step over a lost race.
     */
    boolean updateVariablesIfVersion(UUID id, Map<String, Object> variables, long expectedVersion);

    /** Moves a running instance to a non-terminal status such as WAITING, under the optimistic version. */
    long setStatus(UUID id, InstanceStatus status, long expectedVersion);

    /** Moves the instance to a terminal status once. A second caller finds it already finished and does nothing. */
    void finish(UUID id, InstanceStatus status, Instant finishedAt);

    /**
     * Records why an instance stopped without finishing: a dead letter or a failed compensation. The row keeps no finish
     * time, because an operator can still retry, replay or cancel it.
     */
    void markFailure(UUID id, InstanceStatus status, String failureReason);

    /** Brings a dead-lettered instance back to RUNNING and clears its failure reason, under the optimistic version. */
    long resume(UUID id, long expectedVersion);
}

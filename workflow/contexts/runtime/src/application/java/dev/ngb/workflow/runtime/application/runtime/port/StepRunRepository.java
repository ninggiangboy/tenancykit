package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.step.StepRun;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The append-only step history. */
public interface StepRunRepository {

    void add(StepRun run);

    /** How many steps the instance has taken, which is one of its hard limits. */
    long count(UUID instanceId);

    /**
     * The latest successful output of every node the instance finished, so an expression reading
     * {@code {"step": "<nodeId>"}} sees what that step returned.
     */
    Map<String, Object> outputs(UUID instanceId);

    /**
     * The nodes the instance completed, in completion order. A node that completed more than once appears once, at its
     * latest completion, because compensation walks this list backwards.
     */
    List<String> completedNodeIds(UUID instanceId);

    /** The most recent completed step of one node, which is the evidence a compensation names. */
    Optional<UUID> latestStepId(UUID instanceId, String nodeId);
}

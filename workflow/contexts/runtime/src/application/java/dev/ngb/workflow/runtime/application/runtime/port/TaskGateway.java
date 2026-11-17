package dev.ngb.workflow.runtime.application.runtime.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The door the engine uses to create a human task. The tasks context owns the row; the engine only says what the node
 * asked for, and the creation happens inside the transaction that records the step.
 */
public interface TaskGateway {

    /**
     * @param instanceId the instance that parked on the task
     * @param tokenId the token the task resumes
     * @param nodeId the node that asked for it
     * @param assigneeUser the user id, or null with a group
     * @param assigneeGroup the group code, or null with a user
     * @param escalationGroup the group code that takes over at {@code escalateAt}, or null
     * @param expiresAt when the task expires and the node takes its {@code on_expire} edge, or null
     */
    record Draft(
            UUID instanceId,
            UUID tokenId,
            String nodeId,
            String title,
            Object form,
            UUID assigneeUser,
            String assigneeGroup,
            Instant dueAt,
            String escalationGroup,
            Instant escalateAt,
            Instant expiresAt,
            List<String> outcomes) {

        public Draft {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }
    }

    /** The created task, or the reason the node cannot create it (a missing group, no members). */
    record Result(UUID taskId, String failure) {

        public static Result created(UUID taskId) {
            return new Result(taskId, null);
        }

        public static Result failed(String failure) {
            return new Result(null, failure);
        }

        public boolean failed() {
            return failure != null;
        }
    }

    Result create(Draft draft);
}

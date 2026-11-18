package dev.ngb.workflow.tasks.application.task.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The read side of the task inbox, shaped for the API and carrying no domain aggregate. */
public interface TaskReadRepository {

    /**
     * @param viewerId the user id, or null for a Tenant System that sees every task of its tenant
     * @param status a status to filter by, or null for every task
     */
    List<TaskView> list(UUID viewerId, String status);

    Optional<TaskView> findById(UUID taskId);

    /** The member snapshot the task was created with, which is what visibility is decided on. */
    Set<UUID> assignees(UUID taskId);
}

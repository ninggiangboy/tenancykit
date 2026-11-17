package dev.ngb.workflow.tasks.application.task.port;

import dev.ngb.workflow.tasks.domain.task.Task;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The task rows: created once with its member snapshot, moved by conditional updates, swept by its deadlines. */
public interface TaskRepository {

    void add(Task task, Collection<UUID> assignees);

    /** Locks the row for the change that follows, so two completions cannot both pass their {@code If-Match}. */
    Optional<Task> lockById(UUID taskId);

    /** Writes the row when the stored version is older than the loaded one, and fails otherwise. */
    void update(Task task);

    /** The members the task was created with, or the ones its last reassignment copied. */
    Set<UUID> assignees(UUID taskId);

    /** Replaces the member snapshot; called only by a reassignment, in the same transaction. */
    void replaceAssignees(UUID taskId, Collection<UUID> assignees);

    /** Tasks whose escalation or expiry time has passed, locked in a bounded batch. */
    List<Task> due(Instant now, int limit);

    /** How many tasks still reference a group, which is why a group with open work cannot be deleted. */
    long tasksUsing(UUID groupId);

    /** Cancels every open task of an instance when the instance is cancelled. */
    int cancelOpen(UUID instanceId, Instant now);
}

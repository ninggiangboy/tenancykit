package dev.ngb.workflow.tasks.application.task.port;

import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The groups a task may be assigned to, and the members a Tenant maintains. */
public interface TaskGroupRepository {

    TaskGroup add(TaskGroup group, Set<UUID> members);

    Optional<TaskGroup> findByCode(String code);

    Optional<TaskGroup> findById(UUID groupId);

    /** Locks the row for a change under its {@code If-Match}. */
    Optional<TaskGroup> lockByCode(String code);

    void update(TaskGroup group, Set<UUID> members);

    void delete(UUID groupId);

    List<TaskGroup> all();

    Set<UUID> members(UUID groupId);
}

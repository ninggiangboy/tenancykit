package dev.ngb.workflow.tasks.application.task.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.tasks.application.task.service.TaskAccess;
import java.util.List;
import java.util.UUID;

/** The inbox a caller may see: a user's own and their groups' tasks, or every task for a Tenant System. */
public class TaskQueries {

    private final UnitOfWork unitOfWork;
    private final TaskReadRepository tasks;
    private final UserDirectory users;
    private final CurrentActor currentActor;

    public TaskQueries(
            UnitOfWork unitOfWork, TaskReadRepository tasks, UserDirectory users, CurrentActor currentActor) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.users = users;
        this.currentActor = currentActor;
    }

    public List<TaskView> inbox(String status) {
        Actor actor = currentActor.get();
        UUID viewer = actor.type() == ActorType.USER ? actor.id() : null;
        return unitOfWork.transactional(() -> tasks.list(viewer, status));
    }

    public TaskView get(UUID taskId) {
        Actor actor = currentActor.get();
        return unitOfWork.transactional(() -> {
            TaskView task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("No task exists here."));
            TaskAccess.requireVisible(actor, task.assigneeUserId(), tasks.assignees(task.id()), users);
            return task;
        });
    }
}

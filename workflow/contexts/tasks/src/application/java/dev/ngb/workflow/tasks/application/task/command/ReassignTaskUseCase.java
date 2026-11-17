package dev.ngb.workflow.tasks.application.task.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.service.TaskAccess;
import dev.ngb.workflow.tasks.domain.task.Task;
import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import java.util.Set;
import java.util.UUID;

/** Moves an open task to another user or group, copying the new group's members onto it. */
public class ReassignTaskUseCase {

    public record Command(UUID taskId, ExpectedVersion expected, UUID user, String group) {}

    private final UnitOfWork unitOfWork;
    private final TaskRepository tasks;
    private final TaskGroupRepository groups;
    private final UserDirectory users;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ReassignTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.groups = groups;
        this.users = users;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    public Task execute(Command command) {
        Actor actor = currentActor.get();
        if ((command.user() == null) == (command.group() == null)) {
            throw new BadRequestException("a task is reassigned to one user or one group");
        }
        return unitOfWork.transactional(() -> {
            Task task = tasks
                    .lockById(command.taskId())
                    .orElseThrow(() -> new NotFoundException("No task exists here."));
            TaskAccess.requireVisible(actor, task, tasks.assignees(task.id()), users);
            command.expected().check(task.version());
            UUID groupId = null;
            Set<UUID> members = Set.of();
            if (command.group() != null) {
                TaskGroup group = groups
                        .findByCode(command.group())
                        .orElseThrow(() -> new BadRequestException("no task group " + command.group() + " exists"));
                groupId = group.id();
                members = groups.members(groupId);
                if (members.isEmpty()) {
                    throw new BadRequestException("the task group " + command.group() + " has no members");
                }
            }
            Task reassigned = task.reassigned(command.user(), groupId, clock.now());
            tasks.update(reassigned);
            tasks.replaceAssignees(reassigned.id(), members);
            return reassigned;
        });
    }
}

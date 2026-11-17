package dev.ngb.workflow.tasks.application.task.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.service.TaskAccess;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.util.UUID;

/** A person takes an open task; claiming twice is a conflict, and a Tenant System may not claim at all. */
public class ClaimTaskUseCase {

    public record Command(UUID taskId, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final TaskRepository tasks;
    private final UserDirectory users;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ClaimTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.users = users;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    public Task execute(Command command) {
        Actor actor = currentActor.get();
        TaskAccess.requireUser(actor);
        return unitOfWork.transactional(() -> {
            Task task = tasks
                    .lockById(command.taskId())
                    .orElseThrow(() -> new NotFoundException("No task exists here."));
            TaskAccess.requireVisible(actor, task, tasks.assignees(task.id()), users);
            if (task.status() != dev.ngb.workflow.tasks.domain.task.TaskStatus.OPEN) {
                throw new ConflictException("the task is already " + task.status().name().toLowerCase());
            }
            command.expected().check(task.version());
            Task claimed = task.claimed(actor.id(), clock.now());
            tasks.update(claimed);
            return claimed;
        });
    }
}

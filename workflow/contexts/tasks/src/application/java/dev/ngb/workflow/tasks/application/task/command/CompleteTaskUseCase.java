package dev.ngb.workflow.tasks.application.task.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.tasks.application.task.exception.TaskUnprocessableException;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskTokenResumer;
import dev.ngb.workflow.tasks.application.task.service.FormValidator;
import dev.ngb.workflow.tasks.application.task.service.TaskAccess;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Completes a task with one of its declared decisions and an output that matches its form.
 *
 * <p>A person is acting on their own task; a Tenant System acts on any task of its tenant and must name the person it
 * speaks for, which is recorded as stated rather than verified. The status change and the token's resume happen in one
 * transaction, so a completed task can never leave its workflow waiting.
 */
public class CompleteTaskUseCase {

    public record Command(
            UUID taskId, ExpectedVersion expected, String decision, Object output, String completedBy) {}

    private final UnitOfWork unitOfWork;
    private final TaskRepository tasks;
    private final TaskTokenResumer resumer;
    private final UserDirectory users;
    private final CurrentActor currentActor;
    private final Clock clock;

    public CompleteTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskTokenResumer resumer,
            UserDirectory users,
            CurrentActor currentActor,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.resumer = resumer;
        this.users = users;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    public Task execute(Command command) {
        Actor actor = currentActor.get();
        return unitOfWork.transactional(() -> {
            Task task = tasks
                    .lockById(command.taskId())
                    .orElseThrow(() -> new NotFoundException("No task exists here."));
            TaskAccess.requireVisible(actor, task, tasks.assignees(task.id()), users);
            if (!task.open()) {
                throw new ConflictException("the task is already " + task.status().name().toLowerCase());
            }
            command.expected().check(task.version());
            if (!task.accepts(command.decision())) {
                throw new TaskUnprocessableException(
                        "the decision is not one of " + String.join(", ", task.outcomes()));
            }
            FormValidator.validate(task.form(), command.output());

            String via;
            String by;
            if (actor.type() == ActorType.TENANT_SYSTEM) {
                if (command.completedBy() == null || command.completedBy().isBlank()) {
                    throw new TaskUnprocessableException("a Tenant System names the person it completes for");
                }
                via = "SYSTEM";
                by = command.completedBy();
            } else {
                via = "USER";
                by = actor.auditName();
            }
            Instant now = clock.now();
            Task completed = task.completed(command.decision(), command.output(), by, via, now);
            tasks.update(completed);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision", command.decision());
            payload.put("output", command.output());
            payload.put("completedBy", by);
            payload.put("via", via);
            resumer.resume(completed.tokenId(), "task", payload);
            return completed;
        });
    }
}

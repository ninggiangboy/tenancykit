package dev.ngb.workflow.tasks.application.task.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.domain.task.Task;
import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import dev.ngb.workflow.tasks.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates the task a workflow node parked on. The engine calls this in the transaction that records the step, so a task
 * and the token that waits for it either both exist or neither does.
 *
 * <p>The group's members are copied onto the task here. A group that was deleted makes the node fail with a readable
 * reason instead of silently creating a task nobody can see.
 */
public class CreateTaskUseCase {

    public record Command(
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
            List<String> outcomes) {}

    private final UnitOfWork unitOfWork;
    private final TaskRepository tasks;
    private final TaskGroupRepository groups;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateTaskUseCase(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.groups = groups;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public UUID execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        if (command.title() == null || command.title().isBlank()) {
            throw new BadRequestException("a task needs a title");
        }
        if (command.outcomes() == null || command.outcomes().isEmpty()) {
            throw new BadRequestException("a task names at least one outcome");
        }
        if ((command.assigneeUser() == null) == (command.assigneeGroup() == null)) {
            throw new BadRequestException("a task is assigned to one user or one group");
        }
        return unitOfWork.transactional(() -> {
            UUID groupId = null;
            Set<UUID> members = Set.of();
            if (command.assigneeGroup() != null) {
                TaskGroup group = groups
                        .findByCode(command.assigneeGroup())
                        .orElseThrow(() -> new BadRequestException(
                                "no task group " + command.assigneeGroup() + " exists"));
                groupId = group.id();
                members = groups.members(groupId);
                if (members.isEmpty()) {
                    throw new BadRequestException("the task group " + command.assigneeGroup() + " has no members");
                }
            }
            UUID escalationGroupId = null;
            if (command.escalationGroup() != null) {
                escalationGroupId = groups
                        .findByCode(command.escalationGroup())
                        .map(TaskGroup::id)
                        .orElseThrow(() -> new BadRequestException(
                                "no task group " + command.escalationGroup() + " exists"));
            }
            Instant now = clock.now();
            Task task = new Task(
                    ids.newId(),
                    tenant,
                    command.instanceId(),
                    command.tokenId(),
                    command.nodeId(),
                    command.title(),
                    command.form(),
                    command.assigneeUser(),
                    groupId,
                    TaskStatus.OPEN,
                    List.copyOf(command.outcomes()),
                    command.dueAt(),
                    escalationGroupId,
                    command.escalateAt(),
                    command.expiresAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    now,
                    1);
            tasks.add(task, members);
            return task.id();
        });
    }
}

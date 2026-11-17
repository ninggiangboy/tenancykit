package dev.ngb.workflow.tasks.application.task.service;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.workflow.platform.api.replica.UserDirectory;
import dev.ngb.workflow.platform.api.replica.UserView;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.util.Set;
import java.util.UUID;

/**
 * Who may see and act on a task. Platform's replica holds effective permissions, not roles, so membership is the
 * workflow-owned snapshot: the user a task names, or a member of the group it was assigned to when it started. A user
 * who was disabled in Platform loses access at once because the replica is read on every action.
 *
 * <p>An outsider gets "no such task" rather than "forbidden": the difference would leak that the task exists.
 */
public final class TaskAccess {

    private TaskAccess() {}

    public static void requireVisible(Actor actor, Task task, Set<UUID> members, UserDirectory users) {
        requireVisible(
                actor, task.assigneeUserId(), members, users);
    }

    /** The same rule over the read model, which carries the assignee and nothing else of the aggregate. */
    public static void requireVisible(
            Actor actor, UUID assigneeUserId, Set<UUID> members, UserDirectory users) {
        if (actor.type() == ActorType.TENANT_SYSTEM) {
            return;
        }
        UserView user = users.user(actor.id()).orElse(null);
        if (user == null || !user.active() || !user.tenantId().equals(actor.tenantId())) {
            throw new NotFoundException("No task exists here.");
        }
        boolean assigned = actor.id().equals(assigneeUserId) || members.contains(actor.id());
        if (!assigned) {
            throw new NotFoundException("No task exists here.");
        }
    }

    /** A task only a person takes: a Tenant System acts by completing, never by claiming a seat. */
    public static void requireUser(Actor actor) {
        if (actor.type() != ActorType.USER) {
            throw new NotFoundException("No task exists here.");
        }
    }
}

package dev.ngb.workflow.tasks.application.task.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskTokenResumer;
import dev.ngb.workflow.tasks.domain.task.Task;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fires the deadlines a task carries. At its escalation time the escalation group takes over and its members replace
 * the snapshot; at its expiry time the task expires and the workflow takes the node's {@code on_expire} edge.
 *
 * <p>The state change is a conditional update on the status, so a sweep that runs twice escalates and expires once. The
 * deadlines live on the task row rather than in timers: a task deadline changes the task, and the tasks context owns it.
 */
public class TaskSweep {

    private final UnitOfWork unitOfWork;
    private final TaskRepository tasks;
    private final TaskGroupRepository groups;
    private final TaskTokenResumer resumer;
    private final Clock clock;

    public TaskSweep(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            TaskTokenResumer resumer,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.groups = groups;
        this.resumer = resumer;
        this.clock = clock;
    }

    /** Applies what is due, and answers how many tasks moved. */
    public int runOnce(int limit) {
        Instant now = clock.now();
        List<Task> due = unitOfWork.transactional(() -> tasks.due(now, limit));
        int moved = 0;
        for (Task task : due) {
            boolean escalated = unitOfWork.transactional(() -> escalate(task, now));
            if (escalated) {
                moved++;
                continue;
            }
            if (unitOfWork.transactional(() -> expire(task, now))) {
                moved++;
            }
        }
        return moved;
    }

    private boolean escalate(Task task, Instant now) {
        if (task.escalateAt() == null || task.escalateAt().isAfter(now) || task.escalationGroupId() == null) {
            return false;
        }
        Task current = tasks.lockById(task.id()).orElse(null);
        if (current == null || !current.open() || current.status() == dev.ngb.workflow.tasks.domain.task.TaskStatus.ESCALATED
                || current.escalateAt() == null || current.escalateAt().isAfter(now)) {
            return false;
        }
        Task escalated = current.escalated(null, now);
        tasks.update(escalated);
        tasks.replaceAssignees(escalated.id(), groups.members(escalated.escalationGroupId()));
        return true;
    }

    private boolean expire(Task task, Instant now) {
        if (task.expiresAt() == null || task.expiresAt().isAfter(now)) {
            return false;
        }
        Task current = tasks.lockById(task.id()).orElse(null);
        if (current == null || !current.open() || current.expiresAt() == null || current.expiresAt().isAfter(now)) {
            return false;
        }
        Task expired = current.expired(now);
        tasks.update(expired);
        resumer.resume(expired.tokenId(), "expired", Map.of("expired", true, "taskId", expired.id().toString()));
        return true;
    }
}

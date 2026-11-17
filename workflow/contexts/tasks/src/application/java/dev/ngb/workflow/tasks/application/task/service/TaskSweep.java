package dev.ngb.workflow.tasks.application.task.service;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.application.task.port.TaskTokenResumer;
import dev.ngb.workflow.tasks.domain.task.Task;
import dev.ngb.workflow.tasks.domain.task.event.TaskEvents;
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
    private final EventPublisher events;
    private final IdGenerator ids;

    public TaskSweep(
            UnitOfWork unitOfWork,
            TaskRepository tasks,
            TaskGroupRepository groups,
            TaskTokenResumer resumer,
            Clock clock,
            EventPublisher events,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.tasks = tasks;
        this.groups = groups;
        this.resumer = resumer;
        this.clock = clock;
        this.events = events;
        this.ids = ids;
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
        events.publish(TaskEvents.escalated(escalated, ids.newId(), now));
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
        events.publish(TaskEvents.expired(expired, ids.newId(), now));
        resumer.resume(expired.tokenId(), "expired", Map.of("expired", true, "taskId", expired.id().toString()));
        return true;
    }
}

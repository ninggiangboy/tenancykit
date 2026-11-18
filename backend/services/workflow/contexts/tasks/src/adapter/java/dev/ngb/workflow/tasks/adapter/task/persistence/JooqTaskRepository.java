package dev.ngb.workflow.tasks.adapter.task.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK_ASSIGNEE;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.tasks.application.task.port.TaskRepository;
import dev.ngb.workflow.tasks.domain.task.Task;
import dev.ngb.workflow.tasks.domain.task.TaskStatus;
import dev.ngb.workflow_jooq.tables.records.WorkflowTaskRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The task store. Every move is a conditional update on the version, which is the caller's {@code If-Match}. */
public class JooqTaskRepository implements TaskRepository {

    private static final TypeReference<List<String>> OUTCOMES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqTaskRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Task task, Collection<UUID> assignees) {
        dsl.insertInto(WORKFLOW_TASK)
                .set(WORKFLOW_TASK.ID, task.id())
                .set(WORKFLOW_TASK.TENANT_ID, task.tenantId().value())
                .set(WORKFLOW_TASK.INSTANCE_ID, task.instanceId())
                .set(WORKFLOW_TASK.TOKEN_ID, task.tokenId())
                .set(WORKFLOW_TASK.NODE_ID, task.nodeId())
                .set(WORKFLOW_TASK.TITLE, task.title())
                .set(WORKFLOW_TASK.FORM, jsonb(task.form()))
                .set(WORKFLOW_TASK.ASSIGNEE_USER_ID, task.assigneeUserId())
                .set(WORKFLOW_TASK.ASSIGNEE_GROUP_ID, task.assigneeGroupId())
                .set(WORKFLOW_TASK.STATUS, task.status().name())
                .set(WORKFLOW_TASK.OUTCOMES, JSONB.valueOf(json.writeValueAsString(task.outcomes())))
                .set(WORKFLOW_TASK.DUE_AT, task.dueAt())
                .set(WORKFLOW_TASK.ESCALATION_GROUP_ID, task.escalationGroupId())
                .set(WORKFLOW_TASK.ESCALATE_AT, task.escalateAt())
                .set(WORKFLOW_TASK.EXPIRES_AT, task.expiresAt())
                .set(WORKFLOW_TASK.ON_EXPIRE, task.onExpire())
                .set(WORKFLOW_TASK.DECISION, task.decision())
                .set(WORKFLOW_TASK.OUTPUT, jsonb(task.output()))
                .set(WORKFLOW_TASK.COMPLETED_BY, task.completedBy())
                .set(WORKFLOW_TASK.COMPLETED_VIA, task.completedVia())
                .set(WORKFLOW_TASK.COMPLETED_AT, task.completedAt())
                .set(WORKFLOW_TASK.CREATED_AT, task.createdAt())
                .set(WORKFLOW_TASK.UPDATED_AT, task.updatedAt())
                .set(WORKFLOW_TASK.VERSION, task.version())
                .execute();
        insertAssignees(task.id(), task.tenantId(), assignees);
    }

    @Override
    public Optional<Task> lockById(UUID taskId) {
        return dsl.selectFrom(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.ID.eq(taskId))
                .forUpdate()
                .fetchOptional(this::toDomain);
    }

    @Override
    public void update(Task task) {
        int updated = dsl.update(WORKFLOW_TASK)
                .set(WORKFLOW_TASK.TITLE, task.title())
                .set(WORKFLOW_TASK.FORM, jsonb(task.form()))
                .set(WORKFLOW_TASK.ASSIGNEE_USER_ID, task.assigneeUserId())
                .set(WORKFLOW_TASK.ASSIGNEE_GROUP_ID, task.assigneeGroupId())
                .set(WORKFLOW_TASK.STATUS, task.status().name())
                .set(WORKFLOW_TASK.OUTCOMES, JSONB.valueOf(json.writeValueAsString(task.outcomes())))
                .set(WORKFLOW_TASK.DUE_AT, task.dueAt())
                .set(WORKFLOW_TASK.ESCALATION_GROUP_ID, task.escalationGroupId())
                .set(WORKFLOW_TASK.ESCALATE_AT, task.escalateAt())
                .set(WORKFLOW_TASK.EXPIRES_AT, task.expiresAt())
                .set(WORKFLOW_TASK.DECISION, task.decision())
                .set(WORKFLOW_TASK.OUTPUT, jsonb(task.output()))
                .set(WORKFLOW_TASK.COMPLETED_BY, task.completedBy())
                .set(WORKFLOW_TASK.COMPLETED_VIA, task.completedVia())
                .set(WORKFLOW_TASK.COMPLETED_AT, task.completedAt())
                .set(WORKFLOW_TASK.UPDATED_AT, task.updatedAt())
                .set(WORKFLOW_TASK.VERSION, task.version())
                .where(WORKFLOW_TASK.ID.eq(task.id()))
                .and(WORKFLOW_TASK.VERSION.eq(task.version() - 1))
                .execute();
        if (updated != 1) {
            throw new ConflictException("the task changed under this request");
        }
    }

    @Override
    public Set<UUID> assignees(UUID taskId) {
        return new LinkedHashSet<>(dsl.select(WORKFLOW_TASK_ASSIGNEE.USER_ID)
                .from(WORKFLOW_TASK_ASSIGNEE)
                .where(WORKFLOW_TASK_ASSIGNEE.TASK_ID.eq(taskId))
                .fetch(WORKFLOW_TASK_ASSIGNEE.USER_ID));
    }

    @Override
    public void replaceAssignees(UUID taskId, Collection<UUID> assignees) {
        dsl.deleteFrom(WORKFLOW_TASK_ASSIGNEE).where(WORKFLOW_TASK_ASSIGNEE.TASK_ID.eq(taskId)).execute();
        UUID tenantId = dsl.select(WORKFLOW_TASK.TENANT_ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.ID.eq(taskId))
                .fetchOne(WORKFLOW_TASK.TENANT_ID);
        if (tenantId != null) {
            insertAssignees(taskId, new TenantId(tenantId), assignees);
        }
    }

    @Override
    public List<Task> due(Instant now, int limit) {
        return dsl.selectFrom(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.STATUS.in(
                        TaskStatus.OPEN.name(), TaskStatus.CLAIMED.name(), TaskStatus.ESCALATED.name()))
                .and(WORKFLOW_TASK.ESCALATE_AT.le(now).or(WORKFLOW_TASK.EXPIRES_AT.le(now)))
                .orderBy(DSL.coalesce(WORKFLOW_TASK.ESCALATE_AT, WORKFLOW_TASK.EXPIRES_AT))
                .limit(limit)
                .fetch(this::toDomain);
    }

    @Override
    public long tasksUsing(UUID groupId) {
        return dsl.fetchCount(
                WORKFLOW_TASK,
                WORKFLOW_TASK
                        .STATUS
                        .in(TaskStatus.OPEN.name(), TaskStatus.CLAIMED.name(), TaskStatus.ESCALATED.name())
                        .and(WORKFLOW_TASK.ASSIGNEE_GROUP_ID
                                .eq(groupId)
                                .or(WORKFLOW_TASK.ESCALATION_GROUP_ID.eq(groupId))));
    }

    @Override
    public int cancelOpen(UUID instanceId, Instant now) {
        return dsl.update(WORKFLOW_TASK)
                .set(WORKFLOW_TASK.STATUS, TaskStatus.CANCELLED.name())
                .set(WORKFLOW_TASK.UPDATED_AT, now)
                .set(WORKFLOW_TASK.VERSION, WORKFLOW_TASK.VERSION.plus(1))
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_TASK.STATUS.in(
                        TaskStatus.OPEN.name(), TaskStatus.CLAIMED.name(), TaskStatus.ESCALATED.name()))
                .execute();
    }

    private void insertAssignees(UUID taskId, TenantId tenant, Collection<UUID> assignees) {
        if (assignees == null || assignees.isEmpty()) {
            return;
        }
        for (UUID userId : assignees) {
            dsl.insertInto(WORKFLOW_TASK_ASSIGNEE)
                    .set(WORKFLOW_TASK_ASSIGNEE.TASK_ID, taskId)
                    .set(WORKFLOW_TASK_ASSIGNEE.TENANT_ID, tenant.value())
                    .set(WORKFLOW_TASK_ASSIGNEE.USER_ID, userId)
                    .execute();
        }
    }

    private Task toDomain(WorkflowTaskRecord row) {
        return new Task(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getTokenId(),
                row.getNodeId(),
                row.getTitle(),
                document(row.getForm()),
                row.getAssigneeUserId(),
                row.getAssigneeGroupId(),
                TaskStatus.valueOf(row.getStatus()),
                json.readValue(row.getOutcomes().data(), OUTCOMES),
                row.getDueAt(),
                row.getEscalationGroupId(),
                row.getEscalateAt(),
                row.getExpiresAt(),
                row.getOnExpire(),
                row.getDecision(),
                document(row.getOutput()),
                row.getCompletedBy(),
                row.getCompletedVia(),
                row.getCompletedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private JSONB jsonb(Object value) {
        return value == null ? null : JSONB.valueOf(json.writeValueAsString(value));
    }

    private Object document(JSONB value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }
}

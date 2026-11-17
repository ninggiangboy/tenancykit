package dev.ngb.workflow.tasks.adapter.task.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK_GROUP;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK_GROUP_MEMBER;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.tasks.application.task.port.TaskGroupRepository;
import dev.ngb.workflow.tasks.domain.task.TaskGroup;
import dev.ngb.workflow_jooq.tables.records.WorkflowTaskGroupRecord;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

/** The groups and their members. A member row is the only place a group's people are written. */
public class JooqTaskGroupRepository implements TaskGroupRepository {

    private final DSLContext dsl;

    public JooqTaskGroupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public TaskGroup add(TaskGroup group, Set<UUID> members) {
        dsl.insertInto(WORKFLOW_TASK_GROUP)
                .set(WORKFLOW_TASK_GROUP.ID, group.id())
                .set(WORKFLOW_TASK_GROUP.TENANT_ID, group.tenantId().value())
                .set(WORKFLOW_TASK_GROUP.CODE, group.code())
                .set(WORKFLOW_TASK_GROUP.NAME, group.name())
                .set(WORKFLOW_TASK_GROUP.CREATED_AT, group.createdAt())
                .set(WORKFLOW_TASK_GROUP.UPDATED_AT, group.updatedAt())
                .set(WORKFLOW_TASK_GROUP.VERSION, group.version())
                .execute();
        replaceMembers(group, members);
        return group;
    }

    @Override
    public Optional<TaskGroup> findByCode(String code) {
        return dsl.selectFrom(WORKFLOW_TASK_GROUP)
                .where(WORKFLOW_TASK_GROUP.CODE.eq(code))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<TaskGroup> findById(UUID groupId) {
        return dsl.selectFrom(WORKFLOW_TASK_GROUP)
                .where(WORKFLOW_TASK_GROUP.ID.eq(groupId))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<TaskGroup> lockByCode(String code) {
        return dsl.selectFrom(WORKFLOW_TASK_GROUP)
                .where(WORKFLOW_TASK_GROUP.CODE.eq(code))
                .forUpdate()
                .fetchOptional(this::toDomain);
    }

    @Override
    public void update(TaskGroup group, Set<UUID> members) {
        int updated = dsl.update(WORKFLOW_TASK_GROUP)
                .set(WORKFLOW_TASK_GROUP.NAME, group.name())
                .set(WORKFLOW_TASK_GROUP.UPDATED_AT, group.updatedAt())
                .set(WORKFLOW_TASK_GROUP.VERSION, group.version())
                .where(WORKFLOW_TASK_GROUP.ID.eq(group.id()))
                .and(WORKFLOW_TASK_GROUP.VERSION.eq(group.version() - 1))
                .execute();
        if (updated != 1) {
            throw new ConflictException("the task group changed under this request");
        }
        replaceMembers(group, members);
    }

    @Override
    public void delete(UUID groupId) {
        dsl.deleteFrom(WORKFLOW_TASK_GROUP).where(WORKFLOW_TASK_GROUP.ID.eq(groupId)).execute();
    }

    @Override
    public List<TaskGroup> all() {
        return dsl.selectFrom(WORKFLOW_TASK_GROUP).orderBy(WORKFLOW_TASK_GROUP.CODE).fetch(this::toDomain);
    }

    @Override
    public Set<UUID> members(UUID groupId) {
        return new LinkedHashSet<>(dsl.select(WORKFLOW_TASK_GROUP_MEMBER.USER_ID)
                .from(WORKFLOW_TASK_GROUP_MEMBER)
                .where(WORKFLOW_TASK_GROUP_MEMBER.GROUP_ID.eq(groupId))
                .fetch(WORKFLOW_TASK_GROUP_MEMBER.USER_ID));
    }

    private void replaceMembers(TaskGroup group, Set<UUID> members) {
        dsl.deleteFrom(WORKFLOW_TASK_GROUP_MEMBER)
                .where(WORKFLOW_TASK_GROUP_MEMBER.GROUP_ID.eq(group.id()))
                .execute();
        if (members == null) {
            return;
        }
        Instant now = group.updatedAt();
        for (UUID userId : members) {
            dsl.insertInto(WORKFLOW_TASK_GROUP_MEMBER)
                    .set(WORKFLOW_TASK_GROUP_MEMBER.GROUP_ID, group.id())
                    .set(WORKFLOW_TASK_GROUP_MEMBER.TENANT_ID, group.tenantId().value())
                    .set(WORKFLOW_TASK_GROUP_MEMBER.USER_ID, userId)
                    .set(WORKFLOW_TASK_GROUP_MEMBER.ADDED_AT, now)
                    .execute();
        }
    }

    private TaskGroup toDomain(WorkflowTaskGroupRecord row) {
        return new TaskGroup(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getCode(),
                row.getName(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}

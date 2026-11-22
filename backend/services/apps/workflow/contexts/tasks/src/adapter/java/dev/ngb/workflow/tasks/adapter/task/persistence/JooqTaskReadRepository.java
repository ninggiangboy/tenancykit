package dev.ngb.workflow.tasks.adapter.task.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK_ASSIGNEE;

import dev.ngb.workflow.tasks.application.task.query.TaskReadRepository;
import dev.ngb.workflow.tasks.application.task.query.TaskView;
import dev.ngb.workflow_jooq.tables.records.WorkflowTaskRecord;
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

/** The inbox as the API reads it: one shaped view per row, never a domain aggregate. */
public class JooqTaskReadRepository implements TaskReadRepository {

    private static final TypeReference<List<String>> OUTCOMES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqTaskReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<TaskView> list(UUID viewerId, String status) {
        Condition condition = DSL.trueCondition();
        if (viewerId != null) {
            condition = condition.and(WORKFLOW_TASK.ASSIGNEE_USER_ID
                    .eq(viewerId)
                    .or(DSL.exists(DSL.selectOne()
                            .from(WORKFLOW_TASK_ASSIGNEE)
                            .where(WORKFLOW_TASK_ASSIGNEE.TASK_ID.eq(WORKFLOW_TASK.ID))
                            .and(WORKFLOW_TASK_ASSIGNEE.USER_ID.eq(viewerId)))));
        }
        if (status != null && !status.isBlank()) {
            condition = condition.and(WORKFLOW_TASK.STATUS.eq(status));
        }
        return dsl.selectFrom(WORKFLOW_TASK)
                .where(condition)
                .orderBy(WORKFLOW_TASK.CREATED_AT.desc())
                .fetch(this::toView);
    }

    @Override
    public Optional<TaskView> findById(UUID taskId) {
        return dsl.selectFrom(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.ID.eq(taskId))
                .fetchOptional(this::toView);
    }

    @Override
    public Set<UUID> assignees(UUID taskId) {
        return new LinkedHashSet<>(dsl.select(WORKFLOW_TASK_ASSIGNEE.USER_ID)
                .from(WORKFLOW_TASK_ASSIGNEE)
                .where(WORKFLOW_TASK_ASSIGNEE.TASK_ID.eq(taskId))
                .fetch(WORKFLOW_TASK_ASSIGNEE.USER_ID));
    }

    private TaskView toView(WorkflowTaskRecord row) {
        return new TaskView(
                row.getId(),
                row.getTitle(),
                row.getStatus(),
                row.getInstanceId(),
                row.getNodeId(),
                row.getAssigneeUserId() != null ? "user" : "group",
                row.getAssigneeUserId() != null
                        ? row.getAssigneeUserId().toString()
                        : String.valueOf(row.getAssigneeGroupId()),
                row.getAssigneeUserId(),
                json.readValue(row.getOutcomes().data(), OUTCOMES),
                document(row.getForm()),
                row.getDueAt(),
                row.getEscalateAt(),
                row.getExpiresAt(),
                row.getDecision(),
                document(row.getOutput()),
                row.getCompletedBy(),
                row.getCompletedVia(),
                row.getCompletedAt(),
                row.getCreatedAt(),
                row.getVersion());
    }

    private Object document(JSONB value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }
}

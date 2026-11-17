package dev.ngb.workflow.tasks.adapter.task.web;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow.shared.WorkflowPermissions.TASK_COMPLETE;
import static dev.ngb.workflow.shared.WorkflowPermissions.TASK_READ;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.support.TestTokenFactory;
import dev.ngb.workflow.support.WorkflowApiTest;
import dev.ngb.workflow.tasks.application.task.service.TaskSweep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Human tasks end to end: a user sees their own task, completes it once, and the workflow carries the decision on; an
 * outsider cannot see it; a group task escalates and expires into the node's expiry edge.
 */
class TasksApiTest extends WorkflowApiTest {

    private static final List<String> MANAGE_CODES = List.of(
            WorkflowPermissions.DEFINITION_READ,
            WorkflowPermissions.DEFINITION_MANAGE,
            WorkflowPermissions.INSTANCE_READ,
            WorkflowPermissions.INSTANCE_MANAGE,
            WorkflowPermissions.TASK_READ,
            WorkflowPermissions.TASK_COMPLETE,
            WorkflowPermissions.TASK_MANAGE);

    @Autowired
    private CreateDefinitionUseCase createDefinition;

    @Autowired
    private UpdateDraftUseCase updateDraft;

    @Autowired
    private PublishDefinitionUseCase publishDefinition;

    @Autowired
    private StartInstanceUseCase startInstance;

    @Autowired
    private ClaimAndRun claimAndRun;

    @Autowired
    private TaskSweep taskSweep;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aUserCompletesTheirTaskOnceAndTheWorkflowGoesOn() throws Exception {
        Session session = tenant(MANAGE_CODES.toArray(String[]::new));
        String subject = "approver-" + UUID.randomUUID();
        UUID userId = replica.user(session.tenant().value(), subject, TASK_READ, TASK_COMPLETE);
        Session user = new Session(session.tenant(), session.host(), TestTokenFactory.userToken(subject));

        UUID instance = startAndRun(session, taskGraph(userId, null, "PT1H", null), Map.of());
        assertThat(taskStatus(session, instance)).isEqualTo("OPEN");

        MvcResult listed = getAs(user, "/api/v1/tasks").andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode inbox = json.readTree(listed.getResponse().getContentAsString());
        assertThat(inbox.size()).as("the user's inbox holds their task").isEqualTo(1);
        String taskId = inbox.get(0).get("id").asString();

        String etag = ExpectedVersion.etag(inbox.get(0).get("version").asLong());
        MvcResult completed = send(post("/api/v1/tasks/{id}/complete", taskId), user,
                        "{\"decision\":\"approve\",\"output\":{\"note\":\"ok\"}}", etag)
                .andReturn();
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);

        runToRest(session, instance);
        assertThat(instanceStatus(session, instance)).isEqualTo("COMPLETED");
        assertThat(taskStatus(session, instance)).isEqualTo("COMPLETED");

        MvcResult second = send(post("/api/v1/tasks/{id}/complete", taskId), user,
                        "{\"decision\":\"approve\"}", etag)
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void anOutsiderCannotSeeOrCompleteATask() throws Exception {
        Session session = tenant(MANAGE_CODES.toArray(String[]::new));
        UUID userId = replica.user(session.tenant().value(), "owner-" + UUID.randomUUID(), TASK_READ);
        String outsiderSubject = "outsider-" + UUID.randomUUID();
        replica.user(session.tenant().value(), outsiderSubject, TASK_READ, TASK_COMPLETE);
        Session outsider = new Session(session.tenant(), session.host(), TestTokenFactory.userToken(outsiderSubject));

        UUID instance = startAndRun(session, taskGraph(userId, null, "PT1H", null), Map.of());
        UUID taskId = read(tenantOf(session), () -> dsl.select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instance))
                .fetchOne(WORKFLOW_TASK.ID));

        assertThat(statusCode(getAs(outsider, "/api/v1/tasks/{id}", taskId))).isEqualTo(404);
        assertThat(statusCode(send(post("/api/v1/tasks/{id}/complete", taskId), outsider,
                        "{\"decision\":\"approve\"}", "\"1\"")))
                .isEqualTo(404);
    }

    @Test
    void aGroupTaskEscalatesThenExpiresIntoTheNodesExpiryEdge() throws Exception {
        Session session = tenant(MANAGE_CODES.toArray(String[]::new));
        String subject = "manager-" + UUID.randomUUID();
        UUID member = replica.user(session.tenant().value(), subject, TASK_READ, TASK_COMPLETE);
        Session user = new Session(session.tenant(), session.host(), TestTokenFactory.userToken(subject));

        assertThat(statusCode(postAs(session, "/api/v1/task-groups",
                        "{\"code\":\"managers\",\"name\":\"Managers\",\"members\":[\"" + member + "\"]}")))
                .isEqualTo(201);

        UUID instance = startAndRun(session, groupTaskGraph(), Map.of());
        assertThat(taskStatus(session, instance)).isEqualTo("OPEN");

        inTenant(tenantOf(session), () -> taskSweep.runOnce(10));
        UUID taskId = read(tenantOf(session), () -> dsl.select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instance))
                .fetchOne(WORKFLOW_TASK.ID));
        assertThat(taskStatus(session, instance)).isEqualTo("ESCALATED");

        inTenant(tenantOf(session), () -> taskSweep.runOnce(10));
        assertThat(taskStatus(session, instance)).isEqualTo("EXPIRED");
        assertThat(statusCode(getAs(user, "/api/v1/tasks/{id}", taskId))).isEqualTo(200);

        runToRest(session, instance);
        assertThat(instanceStatus(session, instance)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ helpers

    private UUID startAndRun(Session session, Object graph, Object input) {
        Definition definition = inTenant(tenantOf(session), () -> createDefinition.execute(
                new CreateDefinitionUseCase.Command("flow" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                        "Flow")));
        inTenant(tenantOf(session), () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(definition.id(), graph, ExpectedVersion.any())));
        PublishedVersion version = inTenant(tenantOf(session), () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .version());
        UUID instance = inTenant(tenantOf(session), () -> startInstance
                .execute(new StartInstanceUseCase.Command(version.id(), input, null, "test", null, null))
                .id());
        for (int step = 0; step < 20; step++) {
            boolean claimed = inTenant(tenantOf(session), () -> claimAndRun.runOnce()).claimed();
            if (read(tenantOf(session), () -> dsl.fetchCount(
                            WORKFLOW_TASK,
                            WORKFLOW_TASK.INSTANCE_ID.eq(instance)))
                    > 0) {
                return instance;
            }
            assertThat(claimed).as("the run must reach the task").isTrue();
        }
        throw new AssertionError("the task was not created");
    }

    private void runToRest(Session session, UUID instance) {
        for (int step = 0; step < 50; step++) {
            if (instanceStatus(session, instance).equals("COMPLETED")
                    || instanceStatus(session, instance).equals("FAILED")) {
                return;
            }
            inTenant(tenantOf(session), () -> claimAndRun.runOnce());
        }
        throw new AssertionError("the instance did not finish");
    }

    private TenantId tenantOf(Session session) {
        return session.tenant();
    }

    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    private String instanceStatus(Session session, UUID instance) {
        return read(tenantOf(session), () -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance))
                .fetchOne(WORKFLOW_INSTANCE.STATUS));
    }

    private String taskStatus(Session session, UUID instance) {
        return read(tenantOf(session), () -> dsl.select(WORKFLOW_TASK.STATUS)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instance))
                .orderBy(WORKFLOW_TASK.CREATED_AT.desc())
                .limit(1)
                .fetchOne(WORKFLOW_TASK.STATUS));
    }

    /** trigger -> approve (task) -> approved -> end, with an expiry edge. */
    private static Object taskGraph(UUID userId, String group, String due, String onExpire) {
        Map<String, Object> assignee = userId != null ? Map.of("user", userId.toString()) : Map.of("group", group);
        Map<String, Object> task = new java.util.LinkedHashMap<>();
        task.put("id", "approve");
        task.put("type", "task");
        task.put("title", "Approve the refund");
        task.put("assignee", assignee);
        task.put("outcomes", List.of("approve", "reject"));
        task.put("due", due);
        task.put("next", "approved");
        if (onExpire != null) {
            task.put("on_expire", onExpire);
        }
        List<Object> nodes = new java.util.ArrayList<>(List.of(
                Map.of("id", "start", "type", "trigger", "next", "approve"),
                task,
                Map.of("id", "approved", "type", "set", "values", Map.of("approved", true), "next", "done")));
        if (onExpire != null) {
            nodes.add(Map.of("id", "expired", "type", "set", "values", Map.of("expired", true), "next", "done"));
        }
        nodes.add(Map.of("id", "done", "type", "end"));
        return Map.of("start", "start", "nodes", nodes);
    }

    private static Object groupTaskGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "approve"),
                        Map.of(
                                "id", "approve",
                                "type", "task",
                                "title", "Approve from the group",
                                "assignee", Map.of("group", "managers"),
                                "outcomes", List.of("approve", "reject"),
                                "due", "PT0S",
                                "escalate", Map.of("group", "managers", "due", "PT0S"),
                                "next", "approved",
                                "on_expire", "expired"),
                        Map.of("id", "approved", "type", "set", "values", Map.of("approved", true), "next", "done"),
                        Map.of("id", "expired", "type", "set", "values", Map.of("expired", true), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}

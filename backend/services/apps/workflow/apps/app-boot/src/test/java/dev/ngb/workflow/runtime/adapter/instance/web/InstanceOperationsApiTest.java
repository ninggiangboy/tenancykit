package dev.ngb.workflow.runtime.adapter.instance.web;

import static dev.ngb.workflow.shared.WorkflowPermissions.INSTANCE_MANAGE;
import static dev.ngb.workflow.shared.WorkflowPermissions.INSTANCE_READ;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TASK;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.support.ScriptedHttp;
import dev.ngb.workflow.support.WorkflowApiTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operator API on one instance: retry a dead-lettered step, replay a finished instance from its original input,
 * cancel it and read the redacted timeline.
 */
@Import(InstanceOperationsApiTest.ScriptedHttpConfiguration.class)
class InstanceOperationsApiTest extends WorkflowApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedHttpConfiguration {

        @Bean
        @Primary
        ScriptedHttp scriptedHttp() {
            return new ScriptedHttp();
        }
    }

    private static final List<String> CODES = List.of(INSTANCE_READ, INSTANCE_MANAGE);

    @Autowired private ScriptedHttp http;
    @Autowired private ClaimAndRun claimAndRun;
    @Autowired private CreateDefinitionUseCase createDefinition;
    @Autowired private UpdateDraftUseCase updateDraft;
    @Autowired private PublishDefinitionUseCase publishDefinition;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void forgetTheLastScript() {
        http.reset();
    }

    @Test
    void retryRequeuesTheFailedStepAndTheTimelineShowsWhyItStopped() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        UUID instance = start(session, plainCallGraph(), Map.of("kind", "api"));
        http.answer(ScriptedHttp.status(422));

        runUntilRest(session, instance);
        assertThat(instanceStatus(session, instance)).isEqualTo("FAILED_STUCK");

        MvcResult timeline = getAs(session, "/api/v1/instances/{id}/steps", instance).andReturn();
        assertThat(timeline.getResponse().getStatus()).isEqualTo(200);
        JsonNode steps = json.readTree(timeline.getResponse().getContentAsString());
        assertThat(steps.size()).isEqualTo(2);
        assertThat(steps.get(1).get("nodeId").asString()).isEqualTo("call");
        assertThat(steps.get(1).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(steps.get(1).get("errorClass").asString()).isEqualTo("FATAL");
        assertThat(steps.get(1).get("failure").asString()).isEqualTo("http_call_failed:422");
        assertThat(steps.get(1).get("idempotencyKey").asString()).hasSize(64);
        assertThat(steps.get(1).has("request")).as("the timeline never carries an input payload").isFalse();

        MvcResult retried = postAs(session, "/api/v1/instances/{id}/retry", "{}", instance).andReturn();
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(retried.getResponse().getContentAsString()).get("status").asString())
                .isEqualTo("RUNNING");

        http.reset();
        http.answer(ScriptedHttp.ok("{}"));
        runUntilRest(session, instance);
        assertThat(instanceStatus(session, instance)).isEqualTo("COMPLETED");

        assertThat(statusCode(postAs(session, "/api/v1/instances/{id}/retry", null, instance)))
                .as("an instance that is not dead-lettered cannot be retried")
                .isEqualTo(409);
    }

    @Test
    void replayStartsALinkedInstanceFromTheOriginalInputAndPinnedVersion() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        UUID instance = start(session, setAndEndGraph(), Map.of("kind", "replay"));

        assertThat(statusCode(postAs(session, "/api/v1/instances/{id}/replay", null, instance)))
                .as("an instance that has not finished cannot be replayed")
                .isEqualTo(409);

        runUntilRest(session, instance);
        MvcResult replayed = postAs(session, "/api/v1/instances/{id}/replay", null, instance).andReturn();
        assertThat(replayed.getResponse().getStatus()).isEqualTo(201);
        UUID copy = UUID.fromString(
                json.readTree(replayed.getResponse().getContentAsString()).get("id").asString());

        assertThat(read(session, () -> dsl.select(WORKFLOW_INSTANCE.REPLAYED_FROM)
                        .from(WORKFLOW_INSTANCE)
                        .where(WORKFLOW_INSTANCE.ID.eq(copy))
                        .fetchOne(WORKFLOW_INSTANCE.REPLAYED_FROM)))
                .isEqualTo(instance);
        assertThat(read(session, () -> dsl.select(WORKFLOW_INSTANCE.INPUT)
                        .from(WORKFLOW_INSTANCE)
                        .where(WORKFLOW_INSTANCE.ID.eq(copy))
                        .fetchOne(WORKFLOW_INSTANCE.INPUT))
                .data())
                .contains("replay");
        assertThat(read(session, () -> dsl.select(WORKFLOW_TOKEN.NODE_ID)
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(copy))
                        .fetchOne(WORKFLOW_TOKEN.NODE_ID)))
                .isEqualTo("start");
    }

    @Test
    void cancelStopsTheTokensAndCancelsTheOpenTask() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        UUID assignee = replica.user(session.tenant().value(), "approver-" + UUID.randomUUID());
        UUID instance = start(session, taskGraph(assignee), Map.of("kind", "api"));

        for (int step = 0; step < 10 && read(session, () -> dsl.fetchCount(
                        WORKFLOW_TASK, WORKFLOW_TASK.INSTANCE_ID.eq(instance)))
                == 0; step++) {
            inTenant(session.tenant(), () -> claimAndRun.runOnce());
        }
        assertThat(read(session, () -> dsl.select(WORKFLOW_TASK.STATUS)
                        .from(WORKFLOW_TASK)
                        .where(WORKFLOW_TASK.INSTANCE_ID.eq(instance))
                        .fetchOne(WORKFLOW_TASK.STATUS)))
                .isEqualTo("OPEN");
        assertThat(read(session, () -> dsl.select(WORKFLOW_TOKEN.STATUS)
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(instance))
                        .and(WORKFLOW_TOKEN.NODE_ID.eq("approve"))
                        .fetchOne(WORKFLOW_TOKEN.STATUS)))
                .isEqualTo("WAITING");

        MvcResult cancelled = postAs(session, "/api/v1/instances/{id}/cancel", null, instance).andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(cancelled.getResponse().getContentAsString()).get("status").asString())
                .isEqualTo("CANCELLED");

        assertThat(read(session, () -> dsl.fetchCount(
                        WORKFLOW_TOKEN,
                        WORKFLOW_TOKEN
                                .INSTANCE_ID
                                .eq(instance)
                                .and(WORKFLOW_TOKEN.STATUS.in("READY", "RUNNING", "WAITING")))))
                .as("cancelling leaves no token the engine could still run")
                .isZero();
        assertThat(read(session, () -> dsl.select(WORKFLOW_TOKEN.STATUS)
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(instance))
                        .and(WORKFLOW_TOKEN.NODE_ID.eq("approve"))
                        .fetchOne(WORKFLOW_TOKEN.STATUS)))
                .isEqualTo("CANCELLED");
        assertThat(read(session, () -> dsl.select(WORKFLOW_TASK.STATUS)
                        .from(WORKFLOW_TASK)
                        .where(WORKFLOW_TASK.INSTANCE_ID.eq(instance))
                        .fetchOne(WORKFLOW_TASK.STATUS)))
                .isEqualTo("CANCELLED");
        assertThat(read(session, () -> dsl.fetchCount(
                        WORKFLOW_STEP_RUN, WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance))))
                .as("a cancelled instance records no step after the cancel")
                .isEqualTo(2);
        assertThat(statusCode(postAs(session, "/api/v1/instances/{id}/cancel", null, instance)))
                .as("an instance that already finished cannot be cancelled again")
                .isEqualTo(409);
    }

    // ------------------------------------------------------------------ helpers

    private UUID start(Session session, Object graph, Object input) throws Exception {
        Definition definition = inTenant(session.tenant(), () -> createDefinition.execute(
                new CreateDefinitionUseCase.Command("flow" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                        "Flow")));
        inTenant(session.tenant(), () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(definition.id(), graph, ExpectedVersion.any())));
        PublishedVersion version = inTenant(session.tenant(), () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .version());
        MvcResult started = postAs(
                        session,
                        "/api/v1/instances",
                        json.writeValueAsString(Map.of("definition", definition.id().toString(), "input", input)))
                .andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(
                json.readTree(started.getResponse().getContentAsString()).get("id").asString());
    }

    private void runUntilRest(Session session, UUID instance) {
        for (int step = 0; step < 50; step++) {
            String status = instanceStatus(session, instance);
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                    || "FAILED_STUCK".equals(status) || "COMPENSATION_FAILED".equals(status)) {
                return;
            }
            assertThat(inTenant(session.tenant(), () -> claimAndRun.runOnce()).claimed())
                    .as("the instance is still moving, so a token must be due")
                    .isTrue();
        }
        throw new AssertionError("the instance did not come to rest in 50 steps");
    }

    private String instanceStatus(Session session, UUID instance) {
        return read(session, () -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance))
                .fetchOne(WORKFLOW_INSTANCE.STATUS));
    }

    private <T> T read(Session session, java.util.function.Supplier<T> query) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(query));
    }

    /** trigger -> call -> end. */
    private static Object plainCallGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "call"),
                        Map.of(
                                "id", "call",
                                "type", "http",
                                "app", "crm",
                                "method", "GET",
                                "path", "/orders",
                                "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> set -> end. */
    private static Object setAndEndGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "keep"),
                        Map.of("id", "keep", "type", "set", "values", Map.of("kept", true), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> approve (task) -> approved -> end. */
    private static Object taskGraph(UUID assignee) {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "approve"),
                        Map.of(
                                "id", "approve",
                                "type", "task",
                                "title", "Approve the refund",
                                "assignee", Map.of("user", assignee.toString()),
                                "outcomes", List.of("approve", "reject"),
                                "next", "approved"),
                        Map.of("id", "approved", "type", "set", "values", Map.of("approved", true), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}

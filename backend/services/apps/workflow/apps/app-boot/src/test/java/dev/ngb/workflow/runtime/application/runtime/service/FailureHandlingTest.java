package dev.ngb.workflow.runtime.application.runtime.service;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_COMPENSATION_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.command.RetryInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.ScriptedHttp;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Failure handling end to end: a retryable failure comes back with the same idempotency key, a fatal one takes its
 * {@code on_error} edge or leaves the instance dead-lettered, and a deliberate failure rolls the completed steps back in
 * reverse order.
 */
@Import(FailureHandlingTest.ScriptedHttpConfiguration.class)
class FailureHandlingTest extends WorkflowIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedHttpConfiguration {

        @Bean
        @Primary
        ScriptedHttp scriptedHttp() {
            return new ScriptedHttp();
        }
    }

    @Autowired private ScriptedHttp http;
    @Autowired private StartInstanceUseCase start;
    @Autowired private ClaimAndRun claimAndRun;
    @Autowired private RetryInstanceUseCase retry;
    @Autowired private CreateDefinitionUseCase createDefinition;
    @Autowired private UpdateDraftUseCase updateDraft;
    @Autowired private PublishDefinitionUseCase publishDefinition;
    @Autowired private ReplicaFixtures replica;
    @Autowired private DSLContext dsl;
    @Autowired private UnitOfWork unitOfWork;

    @BeforeEach
    void forgetTheLastScript() {
        http.reset();
    }

    @Test
    void aRetryableFailureSucceedsOnTheThirdAttemptWithTheSameIdempotencyKey() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, retryingCallGraph()));
        http.answer(ScriptedHttp.status(503), ScriptedHttp.status(503), ScriptedHttp.ok("[]"));

        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(http.paths()).containsExactly("/customers", "/customers", "/customers");
        List<String> keys = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                .orderBy(WORKFLOW_STEP_RUN.ATTEMPT)
                .fetch(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY));
        assertThat(keys).hasSize(3);
        assertThat(new HashSet<>(keys)).as("every attempt sends the same key").hasSize(1);
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.ERROR_CLASS)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                        .orderBy(WORKFLOW_STEP_RUN.ATTEMPT)
                        .fetch(WORKFLOW_STEP_RUN.ERROR_CLASS)))
                .containsExactly("RETRYABLE", "RETRYABLE", null);
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.OUTCOME)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                        .orderBy(WORKFLOW_STEP_RUN.ATTEMPT)
                        .fetch(WORKFLOW_STEP_RUN.OUTCOME)))
                .containsExactly("FAILED", "FAILED", "SUCCEEDED");
    }

    @Test
    void aRefusedConnectionIsRetryable() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, retryingCallGraph()));
        http.answer(ScriptedHttp.refused("timeout"), ScriptedHttp.ok("[]"));

        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.ERROR_CLASS)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                        .orderBy(WORKFLOW_STEP_RUN.ATTEMPT)
                        .fetch(WORKFLOW_STEP_RUN.ERROR_CLASS)))
                .containsExactly("RETRYABLE", null);
    }

    @Test
    void aFatalFailureTakesTheOnErrorEdge() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, onErrorGraph()));
        http.answer(ScriptedHttp.status(422));

        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(failureReason(tenant, instance.id())).isNull();
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.ERROR_CLASS)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                        .fetchOne(WORKFLOW_STEP_RUN.ERROR_CLASS)))
                .isEqualTo("FATAL");
        String response = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.RESPONSE)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                        .fetchOne(WORKFLOW_STEP_RUN.RESPONSE))
                .data();
        assertThat(response).contains("FATAL").contains("http_call_failed:422");
        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_STEP_RUN,
                        WORKFLOW_STEP_RUN
                                .INSTANCE_ID
                                .eq(instance.id())
                                .and(WORKFLOW_STEP_RUN.NODE_ID.eq("handled")))))
                .isEqualTo(1);
    }

    @Test
    void aFatalFailureWithoutAnOnErrorEdgeDeadLettersAndARetryFinishesIt() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, plainCallGraph()));
        http.answer(ScriptedHttp.status(422));

        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.FAILED_STUCK);
        assertThat(failureReason(tenant, instance.id())).isEqualTo("http_call_failed:422");
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_TOKEN.STATUS)
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_TOKEN.NODE_ID.eq("call"))
                        .fetchOne(WORKFLOW_TOKEN.STATUS)))
                .isEqualTo("FAILED");

        http.reset();
        http.answer(ScriptedHttp.ok("{}"));
        inTenant(tenant, () -> retry.execute(new RetryInstanceUseCase.Command(instance.id(), null)));
        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(failureReason(tenant, instance.id())).isNull();
        List<String> keys = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                .and(WORKFLOW_STEP_RUN.NODE_ID.eq("call"))
                .orderBy(WORKFLOW_STEP_RUN.ATTEMPT)
                .fetch(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY));
        assertThat(keys).hasSize(2);
        assertThat(new HashSet<>(keys)).as("the retried step sends the key it already sent").hasSize(1);
    }

    @Test
    void compensationRunsInReverseCompletionOrder() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, compensatingGraph()));
        http.answer(ScriptedHttp.ok("{}"), ScriptedHttp.ok("{}"), ScriptedHttp.ok("{}"), ScriptedHttp.ok("{}"));

        runUntilRest(tenant, instance.id());

        assertThat(http.paths()).containsExactly("/first", "/second", "/second/undo", "/first/undo");
        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.FAILED);
        assertThat(failureReason(tenant, instance.id())).isEqualTo("boom");
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_COMPENSATION_RUN.NODE_ID, WORKFLOW_COMPENSATION_RUN.STATUS)
                        .from(WORKFLOW_COMPENSATION_RUN)
                        .where(WORKFLOW_COMPENSATION_RUN.INSTANCE_ID.eq(instance.id()))
                        .orderBy(WORKFLOW_COMPENSATION_RUN.STARTED_AT)
                        .fetch()
                        .stream()
                        .map(row -> row.value1() + ":" + row.value2())
                        .toList()))
                .containsExactly("undoSecond:SUCCEEDED", "undoFirst:SUCCEEDED");
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_COMPENSATION_RUN.STEP_RUN_ID)
                        .from(WORKFLOW_COMPENSATION_RUN)
                        .where(WORKFLOW_COMPENSATION_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_COMPENSATION_RUN.NODE_ID.eq("undoFirst"))
                        .fetchOne(WORKFLOW_COMPENSATION_RUN.STEP_RUN_ID)))
                .isEqualTo(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.ID)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                        .and(WORKFLOW_STEP_RUN.NODE_ID.eq("first"))
                        .fetchOne(WORKFLOW_STEP_RUN.ID)));
    }

    @Test
    void aCompensationThatFailsAfterItsRetriesLeavesTheInstanceCompensationFailed() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, compensatingGraph()));
        http.answer(ScriptedHttp.ok("{}"), ScriptedHttp.ok("{}"), ScriptedHttp.status(503));

        runUntilRest(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo(InstanceStatus.COMPENSATION_FAILED);
        assertThat(failureReason(tenant, instance.id())).isEqualTo("compensation_failed:second");
        assertThat(http.paths()).as("the rollback stops at the step that could not be undone")
                .containsExactly("/first", "/second", "/second/undo");
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_COMPENSATION_RUN.NODE_ID, WORKFLOW_COMPENSATION_RUN.STATUS,
                                WORKFLOW_COMPENSATION_RUN.ATTEMPTS)
                        .from(WORKFLOW_COMPENSATION_RUN)
                        .where(WORKFLOW_COMPENSATION_RUN.INSTANCE_ID.eq(instance.id()))
                        .fetch())
                        .getFirst())
                .satisfies(row -> {
                    assertThat(row.value1()).isEqualTo("undoSecond");
                    assertThat(row.value2()).isEqualTo("FAILED");
                    assertThat(row.value3()).isEqualTo(1);
                });
    }

    // ------------------------------------------------------------------ helpers

    private TenantId tenant() {
        return replica.tenant().id();
    }

    private UUID publishedVersion(TenantId tenant, Object graph) {
        Definition definition = inTenant(tenant, () -> createDefinition.execute(new CreateDefinitionUseCase.Command(
                "flow" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), "Flow")));
        inTenant(tenant, () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(definition.id(), graph, ExpectedVersion.any())));
        PublishedVersion version = inTenant(tenant, () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .version());
        return version.id();
    }

    private Instance startInstance(TenantId tenant, UUID version) {
        return inTenant(tenant, () -> start.execute(
                new StartInstanceUseCase.Command(version, Map.of("kind", "test"), null, "test", null, null)));
    }

    private void runUntilRest(TenantId tenant, UUID instanceId) {
        for (int step = 0; step < 50; step++) {
            InstanceStatus status = status(tenant, instanceId);
            if (status.terminal()
                    || status == InstanceStatus.FAILED_STUCK
                    || status == InstanceStatus.COMPENSATION_FAILED) {
                return;
            }
            assertThat(inTenant(tenant, () -> claimAndRun.runOnce()).claimed())
                    .as("the instance is still moving, so a token must be due")
                    .isTrue();
        }
        throw new AssertionError("the instance did not come to rest in 50 steps");
    }

    private InstanceStatus status(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> InstanceStatus.valueOf(dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.STATUS)));
    }

    private String failureReason(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.FAILURE_REASON)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.FAILURE_REASON));
    }

    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    /** trigger -> call (retried twice) -> end. */
    private static Object retryingCallGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "call"),
                        Map.of(
                                "id", "call",
                                "type", "http",
                                "app", "crm",
                                "method", "GET",
                                "path", "/customers",
                                "retry", Map.of("max", 2, "base", "PT0S", "cap", "PT1M", "jitter", false),
                                "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> call -> handled -> end, with the failure taking the on_error edge. */
    private static Object onErrorGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "call"),
                        Map.of(
                                "id", "call",
                                "type", "http",
                                "app", "crm",
                                "method", "POST",
                                "path", "/charges",
                                "next", "done",
                                "on_error", "handled"),
                        Map.of("id", "handled", "type", "set", "values", Map.of("handled", true), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> call -> end, with no retry and no error edge. */
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

    /** trigger -> first -> second -> fail(compensate), each completed call undone by its own http node. */
    private static Object compensatingGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "first"),
                        Map.of(
                                "id", "first",
                                "type", "http",
                                "app", "crm",
                                "method", "POST",
                                "path", "/first",
                                "next", "second",
                                "compensate", "undoFirst"),
                        Map.of(
                                "id", "second",
                                "type", "http",
                                "app", "crm",
                                "method", "POST",
                                "path", "/second",
                                "next", "boom",
                                "compensate", "undoSecond"),
                        Map.of(
                                "id", "undoFirst",
                                "type", "http",
                                "app", "crm",
                                "method", "DELETE",
                                "path", "/first/undo",
                                "next", "rolledBack"),
                        Map.of(
                                "id", "undoSecond",
                                "type", "http",
                                "app", "crm",
                                "method", "DELETE",
                                "path", "/second/undo",
                                "next", "rolledBack"),
                        Map.of("id", "boom", "type", "fail", "reason", "boom", "compensate", true),
                        Map.of("id", "rolledBack", "type", "end")));
    }
}

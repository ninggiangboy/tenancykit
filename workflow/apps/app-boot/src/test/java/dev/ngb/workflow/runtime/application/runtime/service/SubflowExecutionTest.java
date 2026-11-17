package dev.ngb.workflow.runtime.application.runtime.service;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.application.definition.exception.DefinitionInvalidException;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A sub-workflow starts a child instance, waits for it and carries its result back into the parent. */
class SubflowExecutionTest extends WorkflowIntegrationTest {

    @Autowired
    private StartInstanceUseCase start;

    @Autowired
    private ClaimAndRun claimAndRun;

    @Autowired
    private CreateDefinitionUseCase createDefinition;

    @Autowired
    private UpdateDraftUseCase updateDraft;

    @Autowired
    private PublishDefinitionUseCase publishDefinition;

    @Autowired
    private ReplicaFixtures replica;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private UnitOfWork unitOfWork;

    @Test
    void aChildInstanceRunsAndItsResultResumesTheParent() {
        TenantId tenant = replica.tenant().id();
        String childCode = "child" + suffix();
        UUID childVersion = publish(tenant, childCode, childGraph());

        String parentCode = "parent" + suffix();
        UUID parentVersion = publish(tenant, parentCode, parentGraph(childCode));
        Instance parent = inTenant(tenant, () -> start.execute(
                new StartInstanceUseCase.Command(parentVersion, Map.of("seed", "hello"), null, "test", null, null)));
        runToTheEnd(tenant, parent.id());

        assertThat(status(tenant, parent.id()).name()).isEqualTo("COMPLETED");
        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_INSTANCE,
                        WORKFLOW_INSTANCE.PARENT_INSTANCE_ID.eq(parent.id()))))
                .isEqualTo(1);
        Integer depth = read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.DEPTH)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.PARENT_INSTANCE_ID.eq(parent.id()))
                .fetchOne(WORKFLOW_INSTANCE.DEPTH));
        assertThat(depth).isEqualTo(1);
        JSONB response = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.RESPONSE)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(parent.id()))
                .and(WORKFLOW_STEP_RUN.NODE_ID.eq("sub"))
                .orderBy(WORKFLOW_STEP_RUN.FINISHED_AT.desc())
                .limit(1)
                .fetchOne(WORKFLOW_STEP_RUN.RESPONSE));
        assertThat(response.data()).contains("COMPLETED").contains("hello");
        assertThat(childVersion).isNotNull();
    }

    @Test
    void sixSubWorkflowsDeepIsARefusedLimit() {
        TenantId tenant = replica.tenant().id();
        String previous = null;
        for (int level = 6; level >= 0; level--) {
            String code = "level" + level + suffix();
            UUID version = publish(tenant, code, previous == null ? leafGraph() : parentGraph(previous));
            if (level == 0) {
                Instance root = inTenant(tenant, () -> start.execute(
                        new StartInstanceUseCase.Command(version, Map.of("seed", "x"), null, "test", null, null)));
                runToTheEnd(tenant, root.id());
                assertThat(status(tenant, root.id()).name()).isEqualTo("FAILED");
                UUID deepest = read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.ID)
                        .from(WORKFLOW_INSTANCE)
                        .orderBy(WORKFLOW_INSTANCE.DEPTH.desc())
                        .limit(1)
                        .fetchOne(WORKFLOW_INSTANCE.ID));
                JSONB response = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.RESPONSE)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(deepest))
                        .and(WORKFLOW_STEP_RUN.OUTCOME.eq("FAILED"))
                        .fetchOne(WORKFLOW_STEP_RUN.RESPONSE));
                assertThat(response.data()).contains("sub_workflow_depth");
            }
            previous = code;
        }
    }

    @Test
    void publishingAChainThatReturnsToItselfIsRefused() {
        TenantId tenant = replica.tenant().id();
        String a = "a" + suffix();
        String b = "b" + suffix();
        publish(tenant, a, parentGraph(b));
        Definition draftB = create(tenant, b);
        inTenant(tenant, () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(draftB.id(), parentGraph(a), ExpectedVersion.any())));

        assertThatThrownBy(() -> inTenant(tenant, () -> publishDefinition.execute(
                        new PublishDefinitionUseCase.Command(draftB.id(), ExpectedVersion.any(), "test"))))
                .isInstanceOf(DefinitionInvalidException.class)
                .satisfies(error -> assertThat(((DefinitionInvalidException) error).problems())
                        .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("subflow_cycle")));
    }

    // ------------------------------------------------------------------ helpers

    private Definition create(TenantId tenant, String code) {
        return inTenant(tenant, () -> createDefinition.execute(new CreateDefinitionUseCase.Command(code, "Flow")));
    }

    private UUID publish(TenantId tenant, String code, Object graph) {
        Definition definition = create(tenant, code);
        inTenant(tenant, () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(definition.id(), graph, ExpectedVersion.any())));
        PublishedVersion version = inTenant(tenant, () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .version());
        return version.id();
    }

    private void runToTheEnd(TenantId tenant, UUID instanceId) {
        for (int step = 0; step < 200; step++) {
            boolean claimed = inTenant(tenant, () -> claimAndRun.runOnce()).claimed();
            if (status(tenant, instanceId).terminal()) {
                return;
            }
            assertThat(claimed).as("the instance is still running, so a token must be due").isTrue();
        }
        throw new AssertionError("the instance did not finish in 200 steps");
    }

    private InstanceStatus status(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> InstanceStatus.valueOf(dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.STATUS)));
    }

    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** trigger -> subflow(child) with the whole parent input -> end. */
    private static Object parentGraph(String childCode) {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "sub"),
                        Map.of(
                                "id", "sub",
                                "type", "subflow",
                                "definition", childCode,
                                "input", Map.of("input", ""),
                                "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> set the seed -> end. */
    private static Object childGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "keep"),
                        Map.of("id", "keep", "type", "set", "values", Map.of("got", Map.of("input", "seed")),
                                "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    private static Object leafGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}

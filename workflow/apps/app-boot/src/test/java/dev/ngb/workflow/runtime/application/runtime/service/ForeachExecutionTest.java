package dev.ngb.workflow.runtime.application.runtime.service;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_LOOP_STATE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A for-each runs its body once per item, collects the outputs and, when told to, keeps going past a failed item. */
class ForeachExecutionTest extends WorkflowIntegrationTest {

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

    @Autowired
    private TenantContext tenantContext;

    @Test
    void twentyItemsRunTwentyBodiesAndTheLoopCollectsEveryOutput() {
        TenantId tenant = replica.tenant().id();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            items.add(Map.of("sku", "sku-" + index));
        }
        UUID version = publishedVersion(tenant, collectingLoop());
        UUID instance = startInstance(tenant, version, Map.of("items", items));

        runToTheEnd(tenant, instance);

        assertThat(instanceStatus(tenant, instance)).isEqualTo("COMPLETED");
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_LOOP_STATE.TOTAL, WORKFLOW_LOOP_STATE.STARTED,
                        WORKFLOW_LOOP_STATE.ARRIVED)
                .from(WORKFLOW_LOOP_STATE)
                .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instance))
                .fetchOne()))
                .satisfies(row -> {
                    assertThat(row.value1()).isEqualTo(20);
                    assertThat(row.value2()).isEqualTo(20);
                    assertThat(row.value3()).isEqualTo(20);
                });
        JSONB outputs = read(tenant, () -> dsl.select(WORKFLOW_LOOP_STATE.OUTPUTS)
                .from(WORKFLOW_LOOP_STATE)
                .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instance))
                .fetchOne(WORKFLOW_LOOP_STATE.OUTPUTS));
        assertThat(outputs.data()).contains("\"19\"");
        assertThat(read(tenant, () -> dsl.fetchCount(WORKFLOW_STEP_RUN,
                        WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance))))
                .isEqualTo(43);
    }

    @Test
    void aFailedItemIsCollectedWhenTheLoopSaysContinue() {
        TenantId tenant = replica.tenant().id();
        List<Map<String, Object>> items = List.of(Map.of("sku", "a"), Map.of("sku", "b"), Map.of("sku", "c"));
        UUID version = publishedVersion(tenant, forgivingLoop());
        UUID instance = startInstance(tenant, version, Map.of("items", items));

        runToTheEnd(tenant, instance);

        assertThat(instanceStatus(tenant, instance)).isEqualTo("COMPLETED");
        JSONB errors = read(tenant, () -> dsl.select(WORKFLOW_LOOP_STATE.ERRORS)
                .from(WORKFLOW_LOOP_STATE)
                .where(WORKFLOW_LOOP_STATE.INSTANCE_ID.eq(instance))
                .fetchOne(WORKFLOW_LOOP_STATE.ERRORS));
        assertThat(errors.data()).contains("\"0\"").contains("\"1\"").contains("\"2\"");
        assertThat(read(tenant, () -> dsl.fetchCount(WORKFLOW_STEP_RUN,
                        WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance)
                                .and(WORKFLOW_STEP_RUN.OUTCOME.eq("FAILED")))))
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

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

    private UUID startInstance(TenantId tenant, UUID version, Object input) {
        Instance instance = inTenant(tenant, () -> start.execute(
                new StartInstanceUseCase.Command(version, input, null, "test", null, null)));
        return instance.id();
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

    private String instanceStatus(TenantId tenant, UUID instanceId) {
        return status(tenant, instanceId).name();
    }

    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    /** trigger -> for-each -> body set -> back; five iterations may walk the body at once. */
    private static Object collectingLoop() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "loop"),
                        Map.of(
                                "id", "loop",
                                "type", "foreach",
                                "items", Map.of("input", "items"),
                                "each", "body",
                                "next", "done",
                                "concurrency", 5,
                                "output", Map.of("item", "sku")),
                        Map.of("id", "body", "type", "set", "values", Map.of("last", Map.of("item", "sku")),
                                "next", "loop"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> for-each over a body that always fails, with the loop told to keep going. */
    private static Object forgivingLoop() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "loop"),
                        Map.of(
                                "id", "loop",
                                "type", "foreach",
                                "items", Map.of("input", "items"),
                                "each", "body",
                                "next", "done",
                                "on_item_error", "continue"),
                        Map.of("id", "body", "type", "fail", "reason", "boom"),
                        Map.of("id", "done", "type", "end")));
    }
}

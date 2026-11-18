package dev.ngb.workflow.runtime.application.runtime.service;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;
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
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.runtime.domain.step.IdempotencyKeys;
import dev.ngb.workflow.runtime.domain.token.Token;
import dev.ngb.workflow.runtime.domain.token.TokenStatus;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The execution core's proof: one claim wins, a lost lease is taken over, a finished instance has one step row per node
 * with one stable key, and a small fleet of workers finishes many instances without leaving a token stuck.
 */
class ExecutionCoreTest extends WorkflowIntegrationTest {

    @Autowired
    private StartInstanceUseCase start;

    @Autowired
    private ClaimAndRun claimAndRun;

    @Autowired
    private TokenSweeper sweeper;

    @Autowired
    private TokenRepository tokens;

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
    void twoWorkersAskingForOneTokenOnlyOneWins() {
        TenantId tenant = tenant();
        UUID version = publishedVersion(tenant, twoSetsAndAnEnd());
        Instance instance = startInstance(tenant, version);

        Instant now = Instant.now();
        Token first = inTenant(tenant, () -> unitOfWork.transactional(
                        () -> tokens.claimNext("worker-a", now, now.plusSeconds(60))))
                .orElseThrow();
        var second = inTenant(tenant, () -> unitOfWork.transactional(
                () -> tokens.claimNext("worker-b", now, now.plusSeconds(60))));

        assertThat(second).isEmpty();
        assertThat(first.instanceId()).isEqualTo(instance.id());
        assertThat(first.status()).isEqualTo(TokenStatus.RUNNING);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.leaseOwner()).isEqualTo("worker-a");
    }

    @Test
    void anInstanceRunsToTheEndAndRecordsOneStepPerNode() {
        TenantId tenant = tenant();
        UUID version = publishedVersion(tenant, twoSetsAndAnEnd());
        Instance instance = startInstance(tenant, version);

        runToTheEnd(tenant, instance.id());

        String status = read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance.id()))
                .fetchOne(WORKFLOW_INSTANCE.STATUS));
        Instant finishedAt = read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.FINISHED_AT)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance.id()))
                .fetchOne(WORKFLOW_INSTANCE.FINISHED_AT));
        String variables = read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.VARIABLES)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance.id()))
                .fetchOne(WORKFLOW_INSTANCE.VARIABLES))
                .data();
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(finishedAt).isNotNull();
        assertThat(variables).contains("\"m\": 2");

        List<String> keys = read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY)
                .from(WORKFLOW_STEP_RUN)
                .where(WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))
                .fetch(WORKFLOW_STEP_RUN.IDEMPOTENCY_KEY));
        assertThat(keys).hasSize(4);
        assertThat(keys).allMatch(key -> key.length() == 64);
        assertThat(new HashSet<>(keys)).hasSize(4);
        assertThat(keys).contains(IdempotencyKeys.of(instance.id(), triggerToken(tenant, instance.id()), "start", 0));

        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_TOKEN,
                        WORKFLOW_TOKEN
                                .INSTANCE_ID
                                .eq(instance.id())
                                .and(WORKFLOW_TOKEN.STATUS.in("READY", "RUNNING", "WAITING")))))
                .isZero();
    }

    @Test
    void aLeaseThatRunsOutIsTakenOverAndTheStepHasOneCompletion() {
        TenantId tenant = tenant();
        UUID version = publishedVersion(tenant, twoSetsAndAnEnd());
        Instance instance = startInstance(tenant, version);

        Instant now = Instant.now();
        Token abandoned = inTenant(tenant, () -> unitOfWork.transactional(
                        () -> tokens.claimNext("dead-worker", now, now.minusSeconds(1))))
                .orElseThrow();
        assertThat(abandoned.instanceId()).isEqualTo(instance.id());

        int requeued = inTenant(tenant, () -> sweeper.requeueExpired());
        assertThat(requeued).isEqualTo(1);

        runToTheEnd(tenant, instance.id());

        assertThat(read(tenant, () -> dsl.fetchCount(WORKFLOW_STEP_RUN, WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instance.id()))))
                .isEqualTo(4);
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_TOKEN.ATTEMPT)
                        .from(WORKFLOW_TOKEN)
                        .where(WORKFLOW_TOKEN.ID.eq(abandoned.id()))
                        .fetchOne(WORKFLOW_TOKEN.ATTEMPT)))
                .isEqualTo(2);
    }

    @Test
    void eightWorkersFinishFiftyInstancesWithOneCompletionPerStep() throws Exception {
        TenantId tenant = tenant();
        UUID version = publishedVersion(tenant, twoSetsAndAnEnd());
        List<UUID> instances = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            instances.add(startInstance(tenant, version).id());
        }

        AtomicInteger claims = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                workers.submit(() -> {
                    started.await();
                    try {
                        // Each thread loops until nothing is due; SKIP LOCKED hands out one token at a time.
                        while (tenantContext.callAs(RequestScope.tenant(tenant), () -> claimAndRun.runOnce()).claimed()) {
                            claims.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        // A worker that dies must fail the test, not shrink the count silently.
                        failure.compareAndSet(null, e);
                    }
                    return null;
                });
            }
            started.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        }

        if (failure.get() != null) {
            throw new AssertionError("a worker failed", failure.get());
        }
        assertThat(claims.get()).isEqualTo(200);
        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_INSTANCE,
                        WORKFLOW_INSTANCE.TENANT_ID.eq(tenant.value())
                                .and(WORKFLOW_INSTANCE.STATUS.eq("COMPLETED")))))
                .isEqualTo(50);
        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_STEP_RUN,
                        WORKFLOW_STEP_RUN.INSTANCE_ID.in(instances))))
                .isEqualTo(200);
        assertThat(read(tenant, () -> dsl.select(WORKFLOW_STEP_RUN.TOKEN_ID)
                        .from(WORKFLOW_STEP_RUN)
                        .where(WORKFLOW_STEP_RUN.INSTANCE_ID.in(instances))
                        .fetch(WORKFLOW_STEP_RUN.TOKEN_ID)))
                .hasSize(200);
        assertThat(read(tenant, () -> dsl.fetchCount(
                        WORKFLOW_TOKEN,
                        WORKFLOW_TOKEN
                                .INSTANCE_ID
                                .in(instances)
                                .and(WORKFLOW_TOKEN.STATUS.in("READY", "RUNNING", "WAITING")))))
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private TenantId tenant() {
        ReplicaFixtures.TenantSeed seed = replica.tenant();
        return seed.id();
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

    private void runToTheEnd(TenantId tenant, UUID instanceId) {
        for (int step = 0; step < 50; step++) {
            boolean claimed = inTenant(tenant, () -> claimAndRun.runOnce()).claimed();
            InstanceStatus status = InstanceStatus.valueOf(read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                    .from(WORKFLOW_INSTANCE)
                    .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                    .fetchOne(WORKFLOW_INSTANCE.STATUS)));
            if (status.terminal()) {
                return;
            }
            assertThat(claimed).as("the instance is still running, so a token must be due").isTrue();
        }
        throw new AssertionError("the instance did not finish in 50 steps");
    }

    private UUID triggerToken(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> dsl.select(WORKFLOW_TOKEN.ID)
                .from(WORKFLOW_TOKEN)
                .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_TOKEN.NODE_ID.eq("start"))
                .fetchOne(WORKFLOW_TOKEN.ID));
    }

    /** A read needs the tenant set on a transaction, which is what row-level security reads. */
    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    /** trigger -> set -> set -> end, whose variables prove the second set saw the first one. */
    private static Object twoSetsAndAnEnd() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "first"),
                        Map.of("id", "first", "type", "set", "values", Map.of("n", 1), "next", "second"),
                        Map.of("id", "second", "type", "set", "values", Map.of("m", 2), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}

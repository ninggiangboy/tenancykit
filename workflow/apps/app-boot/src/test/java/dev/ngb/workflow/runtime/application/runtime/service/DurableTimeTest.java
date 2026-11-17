package dev.ngb.workflow.runtime.application.runtime.service;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TIMER;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_WAIT_SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.api.wait.WaitResolver;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Durable time: a delay is a row and survives a restart, an event and a timeout race for one token and only one branch
 * runs, and an event that arrived before the wait registered still satisfies it.
 */
class DurableTimeTest extends WorkflowIntegrationTest {

    @Autowired private StartInstanceUseCase start;
    @Autowired private ClaimAndRun claimAndRun;
    @Autowired private TimerSweep timerSweep;
    @Autowired private WaitResolver waits;
    @Autowired private CreateDefinitionUseCase createDefinition;
    @Autowired private UpdateDraftUseCase updateDraft;
    @Autowired private PublishDefinitionUseCase publishDefinition;
    @Autowired private ReplicaFixtures replica;
    @Autowired private DSLContext dsl;
    @Autowired private UnitOfWork unitOfWork;

    @Test
    void aDelayParksTheTokenUntilItsTimerFires() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, delayGraph()));

        runOnce(tenant);
        runOnce(tenant);
        assertThat(status(tenant, instance.id())).isEqualTo("WAITING");
        assertThat(count(tenant, WORKFLOW_TIMER, WORKFLOW_TIMER.INSTANCE_ID.eq(instance.id()))).isEqualTo(1);
        assertThat(openTokens(tenant, instance.id())).isEqualTo(1);

        assertThat(fireTimers(tenant)).isEqualTo(1);
        runToTheEnd(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo("COMPLETED");
        assertThat(firedTimers(tenant, instance.id())).isEqualTo(1);
        assertThat(steps(tenant, instance.id())).isEqualTo(4);
    }

    @Test
    void anEventWinsTheRaceAndTheTimeoutIsCancelled() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, waitGraph()));

        runOnce(tenant);
        runOnce(tenant);
        assertThat(status(tenant, instance.id())).isEqualTo("WAITING");
        assertThat(count(tenant, WORKFLOW_TIMER, WORKFLOW_TIMER.INSTANCE_ID.eq(instance.id()))).isEqualTo(1);
        assertThat(count(tenant, WORKFLOW_WAIT_SUBSCRIPTION,
                        WORKFLOW_WAIT_SUBSCRIPTION.INSTANCE_ID.eq(instance.id())))
                .isEqualTo(1);

        Instant now = Instant.now();
        assertThat(inTenant(tenant, () -> waits.resolve(
                        "external", "thing.happened", Map.of("data", Map.of("v", 1)), now)))
                .isEqualTo(1);
        assertThat(inTenant(tenant, () -> waits.resolve(
                        "external", "thing.happened", Map.of("data", Map.of("v", 1)), now)))
                .as("a redelivered event resumes nothing")
                .isZero();

        makeTimersDue(tenant, instance.id());
        assertThat(fireTimers(tenant)).as("the winning event cancelled the timeout").isZero();

        runToTheEnd(tenant, instance.id());
        assertThat(status(tenant, instance.id())).isEqualTo("COMPLETED");
        assertThat(steps(tenant, instance.id())).isEqualTo(4);
    }

    @Test
    void aTimeoutWinsAndTheLateEventChangesNothing() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, waitGraph()));

        runOnce(tenant);
        runOnce(tenant);
        makeTimersDue(tenant, instance.id());
        assertThat(fireTimers(tenant)).isEqualTo(1);
        assertThat(inTenant(tenant, () -> waits.resolve(
                        "external", "thing.happened", Map.of("data", Map.of("v", 1)), Instant.now())))
                .as("the subscription was closed when the timeout won")
                .isZero();

        runToTheEnd(tenant, instance.id());
        assertThat(status(tenant, instance.id())).isEqualTo("COMPLETED");
        assertThat(steps(tenant, instance.id())).isEqualTo(4);
    }

    @Test
    void anEventThatArrivedBeforeTheWaitStillSatisfiesIt() {
        TenantId tenant = tenant();
        Instance instance = startInstance(tenant, publishedVersion(tenant, waitGraph()));
        // The event arrives before the wait is reached, but after the instance started.
        insertBufferedEvent(tenant, "external", "thing.happened");

        runToTheEnd(tenant, instance.id());

        assertThat(status(tenant, instance.id())).isEqualTo("COMPLETED");
        assertThat(count(tenant, WORKFLOW_TIMER, WORKFLOW_TIMER.INSTANCE_ID.eq(instance.id()))).isZero();
        assertThat(count(tenant, WORKFLOW_WAIT_SUBSCRIPTION,
                        WORKFLOW_WAIT_SUBSCRIPTION.INSTANCE_ID.eq(instance.id())))
                .isZero();
        assertThat(steps(tenant, instance.id())).isEqualTo(3);
    }

    @Test
    void twentyFiveRacesBetweenTheTimerAndTheEventEachProduceOneBranch() throws Exception {
        TenantId tenant = tenant();
        UUID version = publishedVersion(tenant, zeroTimeoutWaitGraph());
        for (int round = 0; round < 25; round++) {
            Instance instance = startInstance(tenant, version);
            runOnce(tenant);
            runOnce(tenant);
            makeTimersDue(tenant, instance.id());

            java.util.concurrent.atomic.AtomicInteger resolved = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            int value = round;
            try (java.util.concurrent.ExecutorService pair =
                    java.util.concurrent.Executors.newFixedThreadPool(2)) {
                pair.submit(() -> {
                    go.await();
                    resolved.addAndGet(timerSweepSilently(tenant));
                    return null;
                });
                pair.submit(() -> {
                    go.await();
                    resolved.addAndGet(inTenant(tenant, () -> waits.resolve(
                            "external", "thing.happened", Map.of("data", Map.of("v", value)), Instant.now())));
                    return null;
                });
                go.countDown();
                pair.shutdown();
                assertThat(pair.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            assertThat(resolved.get()).as("round %s resumed the wait once", round).isEqualTo(1);

            runToTheEnd(tenant, instance.id());
            assertThat(status(tenant, instance.id())).isEqualTo("COMPLETED");
            assertThat(steps(tenant, instance.id())).isEqualTo(4);
        }
    }

    private int timerSweepSilently(TenantId tenant) {
        try {
            return fireTimers(tenant);
        } catch (RuntimeException e) {
            // Two writers racing on one row may abort one transaction; the loser must resume nothing.
            return 0;
        }
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

    private void runOnce(TenantId tenant) {
        assertThat(inTenant(tenant, () -> claimAndRun.runOnce()).claimed()).isTrue();
    }

    private void runToTheEnd(TenantId tenant, UUID instanceId) {
        for (int step = 0; step < 20; step++) {
            if (status(tenant, instanceId).equals("COMPLETED")) {
                return;
            }
            runOnce(tenant);
        }
        throw new AssertionError("the instance did not finish: " + status(tenant, instanceId));
    }

    private int fireTimers(TenantId tenant) {
        return inTenant(tenant, () -> timerSweep.fireDue(Instant.now(), 20));
    }

    private void makeTimersDue(TenantId tenant, UUID instanceId) {
        read(tenant, () -> dsl.update(WORKFLOW_TIMER)
                .set(WORKFLOW_TIMER.DUE_AT, Instant.now().minusSeconds(1))
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId))
                .execute());
    }

    private void insertBufferedEvent(TenantId tenant, String source, String type) {
        read(tenant, () -> dsl.insertInto(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT)
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.ID, UUID.randomUUID())
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.TENANT_ID, tenant.value())
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.SOURCE, source)
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.EVENT_ID, UUID.randomUUID())
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.EVENT_TYPE, type)
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.PAYLOAD, org.jooq.JSONB.valueOf(
                        "{\"data\":{\"v\":1}}"))
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.RECEIVED_AT, Instant.now())
                .set(dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT.EXPIRES_AT,
                        Instant.now().plusSeconds(3600))
                .execute());
    }

    private String status(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.STATUS));
    }

    private long openTokens(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> (long) dsl.fetchCount(
                WORKFLOW_TOKEN,
                WORKFLOW_TOKEN
                        .INSTANCE_ID
                        .eq(instanceId)
                        .and(WORKFLOW_TOKEN.STATUS.in("READY", "RUNNING", "WAITING"))));
    }

    private long steps(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> (long) dsl.fetchCount(
                WORKFLOW_STEP_RUN, WORKFLOW_STEP_RUN.INSTANCE_ID.eq(instanceId)));
    }

    private long firedTimers(TenantId tenant, UUID instanceId) {
        return read(tenant, () -> (long) dsl.fetchCount(
                WORKFLOW_TIMER,
                WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId).and(WORKFLOW_TIMER.FIRED_AT.isNotNull())));
    }

    private long count(TenantId tenant, org.jooq.Table<?> table, org.jooq.Condition condition) {
        return read(tenant, () -> (long) dsl.fetchCount(table, condition));
    }

    private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
        return inTenant(tenant, () -> unitOfWork.transactional(query));
    }

    /** trigger -> delay -> end. */
    private static Object delayGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "pause"),
                        Map.of("id", "pause", "type", "delay", "duration", "PT0S", "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }

    /** trigger -> wait that times out at once, so the timer and the event are both ready to win. */
    private static Object zeroTimeoutWaitGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "watch"),
                        Map.of(
                                "id", "watch",
                                "type", "wait",
                                "source", "external",
                                "event", "thing.happened",
                                "timeout", "PT0S",
                                "on_timeout", "timedOut",
                                "next", "done"),
                        Map.of("id", "done", "type", "end"),
                        Map.of("id", "timedOut", "type", "end")));
    }

    /** trigger -> wait(event | timeout) -> end, with the timeout taking its own end node. */
    private static Object waitGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "watch"),
                        Map.of(
                                "id", "watch",
                                "type", "wait",
                                "source", "external",
                                "event", "thing.happened",
                                "timeout", "PT1H",
                                "on_timeout", "timedOut",
                                "next", "done"),
                        Map.of("id", "done", "type", "end"),
                        Map.of("id", "timedOut", "type", "end")));
    }
}

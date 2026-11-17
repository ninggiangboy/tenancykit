package dev.ngb.workflow.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.runtime.application.runtime.port.LoopStateRepository;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the loop's progress is serialised: many body arrivals at the same instant each get a different next item, and
 * the last one completes the loop exactly once.
 */
class LoopStateConcurrencyTest extends WorkflowIntegrationTest {

    private static final int ITEMS = 50;

    @Autowired
    private LoopStateRepository loops;

    @Autowired
    private UnitOfWork unitOfWork;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private ReplicaFixtures replica;

    @Test
    void fiftyConcurrentArrivalsStartFiftyDistinctIterationsAndCompleteOnce() throws Exception {
        TenantId tenant = replica.tenant().id();
        UUID instance = UUID.randomUUID();
        List<Object> items = new ArrayList<>();
        for (int index = 0; index < ITEMS; index++) {
            items.add("item-" + index);
        }

        inTenant(tenant, () -> unitOfWork.transactional(
                () -> loops.start(tenant, instance, "loop", items, 1, Instant.now())));

        List<Integer> started = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Boolean> completions = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicError failure = new AtomicError();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(16)) {
            for (int worker = 0; worker < ITEMS; worker++) {
                int arrival = worker;
                workers.submit(() -> {
                    go.await();
                    try {
                        var advance = inTenant(tenant, () -> unitOfWork.transactional(() -> loops.advance(
                                tenant, instance, "loop", arrival, null, null, true, Instant.now())));
                        advance.toStart().forEach(iteration -> started.add(iteration.index()));
                        completions.add(advance.complete());
                    } catch (RuntimeException e) {
                        failure.compareAndSet(e);
                    }
                    return null;
                });
            }
            go.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        if (failure.get() != null) {
            throw new AssertionError("a loop arrival failed", failure.get());
        }
        assertThat(started).doesNotHaveDuplicates().hasSize(ITEMS - 1);
        assertThat(started).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.range(1, ITEMS).boxed().toList());
        assertThat(completions).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    private static final class AtomicError {
        private final java.util.concurrent.atomic.AtomicReference<RuntimeException> value =
                new java.util.concurrent.atomic.AtomicReference<>();

        void compareAndSet(RuntimeException error) {
            value.compareAndSet(null, error);
        }

        RuntimeException get() {
            return value.get();
        }
    }
}

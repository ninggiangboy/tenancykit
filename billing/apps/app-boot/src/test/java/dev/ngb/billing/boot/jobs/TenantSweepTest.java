package dev.ngb.billing.boot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.billing.boot.replica.ReplicaTenants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The sweep runs every tenant once, bounds the concurrency and keeps one failure from stopping the rest. */
class TenantSweepTest {

    private static List<UUID> tenants(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static TenantSweep sweep(int parallelism, List<UUID> views, ScopedTenantContext context) {
        UnitOfWork unitOfWork = new UnitOfWork() {
            @Override
            public <T> T transactional(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        ReplicaTenants tenants = new ReplicaTenants(unitOfWork, null) {
            @Override
            public List<UUID> activeIds() {
                return views;
            }
        };
        return new TenantSweep(context, tenants, parallelism);
    }

    @Test
    void everyTenantRunsExactlyOnceAndNeverMoreThanThePermitsAtOnce() {
        List<UUID> views = tenants(40);
        TenantSweep sweep = sweep(4, views, new ScopedTenantContext());
        ConcurrentHashMap<TenantId, AtomicInteger> runs = new ConcurrentHashMap<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        sweep.forEachActiveTenant(tenant -> {
            runs.computeIfAbsent(tenant, key -> new AtomicInteger()).incrementAndGet();
            maximum.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        });

        assertThat(runs).hasSize(40);
        assertThat(runs.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(maximum).hasValue(4);
    }

    @Test
    void theTenantScopeIsBoundInsideTheTaskThread() {
        List<UUID> views = tenants(10);
        ScopedTenantContext context = new ScopedTenantContext();
        TenantSweep sweep = sweep(3, views, context);
        AtomicReference<Throwable> mismatch = new AtomicReference<>();

        sweep.forEachActiveTenant(tenant -> {
            try {
                // The scope was bound by the sweep on this task's own thread, so requireTenant() answers.
                assertThat(context.requireTenant()).isEqualTo(tenant);
            } catch (Throwable failure) {
                mismatch.set(failure);
            }
        });

        assertThat(mismatch.get()).isNull();
    }

    @Test
    void aFailingTenantDoesNotStopTheOthers() {
        List<UUID> views = tenants(40);
        TenantSweep sweep = sweep(4, views, new ScopedTenantContext());
        AtomicInteger ran = new AtomicInteger();

        sweep.forEachActiveTenant(tenant -> {
            if (ran.incrementAndGet() == 1) {
                throw new IllegalStateException("the first tenant's work failed");
            }
        });

        assertThat(ran).as("every tenant was handed to the consumer").hasValue(40);
    }

    @Test
    void aBlockedTenantDoesNotStopALaterOneFromStarting() throws Exception {
        List<UUID> views = tenants(4);
        TenantSweep sweep = sweep(4, views, new ScopedTenantContext());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CopyOnWriteArrayList<TenantId> started = new CopyOnWriteArrayList<>();

        Thread caller = Thread.ofPlatform().start(() -> sweep.forEachActiveTenant(tenant -> {
            started.add(tenant);
            if (tenant.equals(new TenantId(views.get(0)))) {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        assertThat(blocked.await(5, TimeUnit.SECONDS)).as("the first tenant is stuck").isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (started.size() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(started).as("the other tenants ran while the first was blocked").hasSize(4);

        release.countDown();
        caller.join(5_000);
        assertThat(caller.isAlive()).isFalse();
    }

    @Test
    void parallelismOfOneRunsEverythingOnTheCallingThread() {
        List<UUID> views = tenants(5);
        TenantSweep sweep = sweep(1, views, new ScopedTenantContext());
        Thread caller = Thread.currentThread();
        AtomicInteger offThread = new AtomicInteger();

        sweep.forEachActiveTenant(tenant -> {
            if (Thread.currentThread() != caller) {
                offThread.incrementAndGet();
            }
        });

        assertThat(offThread).hasValue(0);
    }
}

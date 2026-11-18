package dev.ngb.platform.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Makes a race between two concurrent changes deterministic. The first change runs inside a transaction that stays
 * open until the second one is seen waiting on a row lock in PostgreSQL. By then the second change has passed its own
 * reads and reached the statement that blocks on the first one's row, which is the interleaving a thread pool only
 * hits by luck.
 */
public final class UncommittedRace {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** What each change returned. */
    public record Outcome<A, B>(A first, B second) {}

    private UncommittedRace() {}

    /**
     * @param waitingOn an {@code ilike} pattern for the statement the second change must be blocked in, such as
     *     {@code select%from%users%for update%}
     */
    public static <A, B> Outcome<A, B> run(
            UnitOfWork unitOfWork, TenantContext tenantContext, RequestScope scope, OwnerDatabase owner, String waitingOn,
            Supplier<A> first, Supplier<B> second) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> one = pool.submit(() -> tenantContext.callAs(scope, () -> unitOfWork.transactional(() -> {
                A result = first.get();
                written.countDown();
                awaitQuietly(release);
                return result;
            })));
            Instant deadline = Instant.now().plus(PATIENCE);
            while (!written.await(50, TimeUnit.MILLISECONDS)) {
                if (one.isDone()) {
                    one.get();
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("the first change did not finish its work in " + PATIENCE);
                }
            }

            Future<B> two = pool.submit(() -> tenantContext.callAs(scope, second));
            while (waitingOnLock(owner, waitingOn) == 0) {
                if (two.isDone()) {
                    release.countDown();
                    throw new AssertionError("the second change finished without waiting for the first one's row: " + describe(two));
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("the second change never blocked in a statement like " + waitingOn);
                }
                Thread.sleep(20);
            }
            release.countDown();
            return new Outcome<>(one.get(PATIENCE.toSeconds(), TimeUnit.SECONDS), two.get(PATIENCE.toSeconds(), TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static long waitingOnLock(OwnerDatabase owner, String waitingOn) {
        return owner.count("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and query ilike ?",
                waitingOn);
    }

    private static String describe(Future<?> done) {
        try {
            return String.valueOf(done.get());
        } catch (ExecutionException e) {
            return String.valueOf(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("the race was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

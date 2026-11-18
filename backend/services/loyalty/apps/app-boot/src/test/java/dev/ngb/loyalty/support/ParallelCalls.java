package dev.ngb.loyalty.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * Runs many calls at once so a test can prove what concurrent callers do to one row.
 *
 * <p>The pool is bounded because the test harness holds four database connections, so a wider burst would only queue on
 * the pool and time out instead of racing on the row under test. Every call is released by the same latch, so the tasks
 * really start together, and the whole batch has a deadline: a call that never answers fails the test rather than
 * hanging it.
 */
public final class ParallelCalls {

    /** How long the whole batch may take before the test is considered hung. */
    private static final long DEADLINE_SECONDS = 60L;

    private ParallelCalls() {}

    /**
     * Runs {@code tasks} calls, at most {@code threads} at a time, all starting as close together as the pool allows.
     *
     * @param task receives the zero-based index of the call, so a caller can vary the reference or the body
     * @return the answers in the order the calls were submitted
     */
    public static <T> List<T> run(int tasks, int threads, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    await(start);
                    return task.apply(index);
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>(tasks);
            for (Future<T> future : futures) {
                answers.add(await(future));
            }
            return List.copyOf(answers);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the calls did not start within " + DEADLINE_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a parallel call was interrupted", e);
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a parallel call was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("a parallel call failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("a parallel call did not answer within " + DEADLINE_SECONDS + " seconds", e);
        }
    }
}

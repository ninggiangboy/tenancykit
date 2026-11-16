package dev.ngb.attribution.tracking.adapter.ingest.memory;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.attribution.tracking.api.ingest.ClickIngest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bounded in-memory hand-off between a redirect and the broker.
 *
 * <p>A click is offered here and the redirect returns its {@code 302} immediately; a single flusher thread waits on the
 * queue and hands batches to the publisher. The queue is bounded so a broker outage cannot grow memory without limit:
 * when it is full the oldest event is dropped, the drop is counted under {@code attribution.ingest.dropped}, and the
 * redirect still answers. A drop is the one loss the app can have, and the metric is what makes it visible.
 *
 * <p>The buffer holds no address and no user agent — a {@link ClickEvent} was already classified — so a heap dump during
 * an outage reveals nothing about a visitor.
 */
public class IngestBuffer implements ClickIngest {

    /** What a publisher receives: a batch of events drained in one pass. */
    @FunctionalInterface
    public interface BatchConsumer {
        /** @return how many events the batch actually carried into the broker */
        int accept(List<ClickEvent> batch);
    }

    private final BlockingQueue<ClickEvent> queue;
    private final int batchSize;
    private final AtomicLong dropped = new AtomicLong();
    private final Counter droppedCounter;
    private final Counter offeredCounter;
    private volatile BatchConsumer consumer;

    /**
     * @param capacity how many events the buffer holds
     * @param batchSize how many events one batch carries
     * @param meters the registry the drop and depth gauges are published to
     */
    public IngestBuffer(int capacity, int batchSize, MeterRegistry meters) {
        if (capacity < 1 || batchSize < 1) {
            throw new IllegalArgumentException("the ingest buffer and its batch size must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.droppedCounter = Counter.builder("attribution.ingest.dropped")
                .description("Clicks dropped because the ingest buffer was full during a broker outage")
                .register(meters);
        this.offeredCounter = Counter.builder("attribution.ingest.offered")
                .description("Clicks a redirect offered to the ingest buffer")
                .register(meters);
        Gauge.builder("attribution.ingest.buffered", queue, BlockingQueue::size)
                .description("Clicks waiting in the ingest buffer")
                .register(meters);
        Gauge.builder("attribution.ingest.dropped.total", dropped, AtomicLong::get)
                .description("Clicks dropped since start, including the counter's own tally")
                .register(meters);
    }

    /** Attach the publisher once it exists; the redirect may offer before the worker's flusher is wired. */
    public void start(BatchConsumer batchConsumer) {
        this.consumer = batchConsumer;
    }

    @Override
    public boolean offer(ClickEvent event) {
        offeredCounter.increment();
        if (queue.offer(event)) {
            return true;
        }
        // Full: drop the oldest so a sustained outage still keeps the most recent clicks and never blocks a redirect.
        queue.poll();
        dropped.incrementAndGet();
        droppedCounter.increment();
        return queue.offer(event);
    }

    /** The number of events dropped since start, for a health check and the load test's loss assertion. */
    public long droppedTotal() {
        return dropped.get();
    }

    /** The number waiting right now, so a drain in a test can wait for zero. */
    public int depth() {
        return queue.size();
    }

    /**
     * Drain the buffer into the publisher, one batch per pass.
     *
     * <p>Called by the flusher on its own thread. It blocks up to the timeout for the first event so an idle app parks;
     * once a batch is formed it hands it over and returns, so the caller loop's interval bounds the worst-case wait.
     *
     * @return how many events the publisher accepted, or negative when no consumer is attached yet
     */
    public int drainOnce(long timeoutMillis) {
        BatchConsumer target = consumer;
        if (target == null) {
            return -1;
        }
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        try {
            ClickEvent first = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (first == null) {
                return 0;
            }
            batch.add(first);
            queue.drainTo(batch, batchSize - 1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        }
        return target.accept(batch);
    }
}

package dev.ngb.attribution.tracking.adapter.ingest.amqp;

import dev.ngb.attribution.tracking.adapter.ingest.memory.IngestBuffer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thread that drains the ingest buffer into the broker.
 *
 * <p>It runs a tight loop rather than a fixed-rate schedule: each pass blocks on the buffer for at most the flush
 * interval, so an idle app parks instead of polling, and a full buffer is drained back-to-back. The loop owns the only
 * thread that touches the publisher, so batches never race.
 *
 * <p>Started by the api runtime, because that is where a click is offered. The worker consumes the same exchange, so a
 * co-located dev process both produces and consumes; the two runtimes are separate in a deployment.
 */
public class IngestFlusher {

    private static final Logger LOG = LoggerFactory.getLogger(IngestFlusher.class);

    private final IngestBuffer buffer;
    private final ClickPublisher publisher;
    private final long pollMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "attribution-ingest-flusher");
                thread.setDaemon(true);
                return thread;
            });

    public IngestFlusher(IngestBuffer buffer, ClickPublisher publisher, Duration flushInterval) {
        this.buffer = buffer;
        this.publisher = publisher;
        this.pollMillis = Math.max(1, flushInterval.toMillis());
    }

    /** Attach the consumer the buffer hands a batch to and start draining. Idempotent. */
    public void start() {
        buffer.start(publisher::publish);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::loop);
    }

    /** Stop draining and give the last batch a moment to leave. */
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                // A batch mid-publish may still land; the buffer is in memory and this is shutdown, so nothing is lost
                // that a restart would have kept anyway.
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                buffer.drainOnce(pollMillis);
            } catch (RuntimeException failure) {
                // A publish failure is already counted; the loop must keep draining or the buffer fills silently.
                LOG.warn("an ingest batch failed to publish; the flusher keeps draining", failure);
            }
        }
    }
}

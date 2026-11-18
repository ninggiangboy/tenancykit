package dev.ngb.attribution.tracking.adapter.ingest.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.TrafficClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The buffer is the one place a click can be lost, so its bound and its drop counter are what the load test's
 * "zero loss when the broker is healthy" claim rests on.
 */
class IngestBufferTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    void anOfferedClickIsDrainedToTheConsumer() {
        IngestBuffer buffer = new IngestBuffer(10, 5, new SimpleMeterRegistry());
        List<ClickEvent> received = new ArrayList<>();
        buffer.start(batch -> {
            received.addAll(batch);
            return batch.size();
        });

        assertThat(buffer.offer(click())).isTrue();
        assertThat(buffer.drainOnce(10)).isEqualTo(1);

        assertThat(received).hasSize(1);
        assertThat(buffer.droppedTotal()).isZero();
    }

    @Test
    void aFullBufferDropsTheOldestAndCountsTheLoss() {
        IngestBuffer buffer = new IngestBuffer(2, 5, new SimpleMeterRegistry());
        for (int index = 0; index < 5; index++) {
            buffer.offer(click());
        }

        assertThat(buffer.depth()).isEqualTo(2);
        assertThat(buffer.droppedTotal()).isEqualTo(3);
    }

    @Test
    void theGaugesArePublishedSoAnOutageIsVisible() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        IngestBuffer buffer = new IngestBuffer(1, 5, meters);
        buffer.offer(click());
        buffer.offer(click());

        assertThat(meters.get("attribution.ingest.dropped").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("attribution.ingest.offered").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("attribution.ingest.buffered").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void aBatchIsCappedSoOneDrainDoesNotHandOverTheWholeBuffer() {
        IngestBuffer buffer = new IngestBuffer(10, 3, new SimpleMeterRegistry());
        AtomicInteger received = new AtomicInteger();
        buffer.start(batch -> {
            received.addAndGet(batch.size());
            return batch.size();
        });
        for (int index = 0; index < 10; index++) {
            buffer.offer(click());
        }

        buffer.drainOnce(10);

        assertThat(received.get()).isEqualTo(3);
        assertThat(buffer.depth()).isEqualTo(7);
    }

    @Test
    void drainingWithNoConsumerAttachedDoesNothing() {
        IngestBuffer buffer = new IngestBuffer(10, 5, new SimpleMeterRegistry());
        buffer.offer(click());

        assertThat(buffer.drainOnce(5)).isNegative();
        assertThat(buffer.depth()).isEqualTo(1);
    }

    private static ClickEvent click() {
        return new ClickEvent(
                UUID.randomUUID(), new TenantId(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, NOW, DeviceClass.DESKTOP, "Chrome", "US", "example.com", null,
                TrafficClass.HUMAN, "hash");
    }
}

package dev.ngb.core.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** The shared failure policy: a contention is not an attempt, a reason is clipped, and the metric names are one scheme. */
class OutboxRowsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void aLockTimeoutOrDeadlockIsContention() {
        assertThat(OutboxRows.contended(new RuntimeException(new SQLException("lock timeout", "55P03")))).isTrue();
        assertThat(OutboxRows.contended(new RuntimeException(new SQLException("deadlock", "40P01")))).isTrue();
        assertThat(OutboxRows.contended(new IllegalStateException("consumer exploded"))).isFalse();
    }

    @Test
    void aFailureReasonIsClippedToTheColumnWidth() {
        String reason = OutboxRows.failureReason(new IllegalStateException("x".repeat(OutboxRows.MAX_REASON * 2)));

        assertThat(reason).hasSize(OutboxRows.MAX_REASON);
    }

    @Test
    void theMetricNamesAreOneScheme() {
        OutboxRows.delivered(meters, "billing", 2);
        OutboxRows.dead(meters, "billing", 1);
        OutboxRows.published(meters, "billing", 3);
        OutboxRows.publishDead(meters, "billing", 1);

        assertThat(meters.counter("billing.outbox.delivered").count()).isEqualTo(2);
        assertThat(meters.counter("billing.outbox.dead").count()).isEqualTo(1);
        assertThat(meters.counter("billing.outbox.publish.delivered").count()).isEqualTo(3);
        assertThat(meters.counter("billing.outbox.publish.dead").count()).isEqualTo(1);
    }
}

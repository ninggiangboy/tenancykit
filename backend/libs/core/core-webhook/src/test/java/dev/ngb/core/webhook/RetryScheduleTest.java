package dev.ngb.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.util.Backoff;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryScheduleTest {

    private final RetrySchedule schedule =
            new RetrySchedule(3, new Backoff(Duration.ofSeconds(30), Duration.ofHours(6), 0.2), Duration.ofMinutes(2));

    @Test
    void theBudgetIsUsedUpAtTheLastAttempt() {
        assertThat(schedule.exhausted(1)).isFalse();
        assertThat(schedule.exhausted(2)).isFalse();
        assertThat(schedule.exhausted(3)).isTrue();
    }

    @Test
    void retriesBackOffExponentially() {
        assertThat(schedule.retryDelay(1, 0.5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(schedule.retryDelay(2, 0.5)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsAnEmptyBudget() {
        assertThatThrownBy(() -> new RetrySchedule(0, schedule.backoff(), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    }
}

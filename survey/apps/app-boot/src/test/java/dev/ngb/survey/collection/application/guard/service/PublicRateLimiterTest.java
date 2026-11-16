package dev.ngb.survey.collection.application.guard.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The public surface's two guards: a bucket that refuses a caller once its allowance is spent and refills over time,
 * and a honeypot a bot fills while a browser never sees it.
 */
class PublicRateLimiterTest {

    @Test
    void aCallerSpendsItsAllowanceAndThenWaits() {
        AtomicLong clock = new AtomicLong(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli());
        PublicRateLimiter limiter = new PublicRateLimiter(3, clock::get);

        assertThat(limiter.refuse("caller")).isEmpty();
        assertThat(limiter.refuse("caller")).isEmpty();
        assertThat(limiter.refuse("caller")).isEmpty();

        OptionalLong wait = limiter.refuse("caller");
        assertThat(wait).as("the fourth call in the same minute is refused").isPresent();
        assertThat(wait.getAsLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anotherCallerHasItsOwnBucket() {
        PublicRateLimiter limiter = new PublicRateLimiter(1, () -> 0L);

        assertThat(limiter.refuse("first")).isEmpty();
        assertThat(limiter.refuse("first")).isPresent();
        assertThat(limiter.refuse("second")).as("one caller cannot spend another's allowance").isEmpty();
    }

    @Test
    void theAllowanceRefillsWithTime() {
        AtomicLong clock = new AtomicLong(0);
        PublicRateLimiter limiter = new PublicRateLimiter(1, clock::get);

        assertThat(limiter.refuse("caller")).isEmpty();
        assertThat(limiter.refuse("caller")).isPresent();

        clock.addAndGet(60_000);
        assertThat(limiter.refuse("caller")).as("a minute later the bucket is full again").isEmpty();
    }

    @Test
    void aZeroSettingDisablesTheLimit() {
        PublicRateLimiter limiter = new PublicRateLimiter(0, () -> 0L);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.refuse("caller")).isEmpty();
        }
    }

    @Test
    void theHoneypotIsFilledOnlyWhenABotAnswersTheDecoy() {
        String name = Honeypot.nameFor(java.util.UUID.randomUUID());

        assertThat(Honeypot.filled(name, java.util.Map.of())).isFalse();
        assertThat(Honeypot.filled(name, java.util.Map.of(name, " "))).isFalse();
        assertThat(Honeypot.filled(name, java.util.Map.of(name, "http://spam.example"))).isTrue();
    }

    @Test
    void twoVisitsNeverShareADecoyName() {
        assertThat(Honeypot.nameFor(java.util.UUID.randomUUID()))
                .isNotEqualTo(Honeypot.nameFor(java.util.UUID.randomUUID()));
    }
}

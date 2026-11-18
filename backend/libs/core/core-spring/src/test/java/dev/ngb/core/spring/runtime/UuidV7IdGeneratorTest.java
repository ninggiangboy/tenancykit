package dev.ngb.core.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The time-ordered id: RFC 9562 version 7, the RFC 4122 variant, and the timestamp the injected clock reports. */
class UuidV7IdGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:15:30.123Z");

    private final Clock clock = () -> NOW;
    private final UuidV7IdGenerator ids = new UuidV7IdGenerator(clock);

    @Test
    void everyIdIsVersionSevenAndTheRfc4122Variant() {
        UUID id = ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void theTimestampComesFromTheClock() {
        UUID id = ids.newId();

        assertThat(id.getMostSignificantBits() >>> 16)
                .as("the 48 bits above the version are the clock's epoch milliseconds")
                .isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void theClockDecidesTheOrdering() {
        UuidV7IdGenerator later = new UuidV7IdGenerator(() -> NOW.plusMillis(1));

        UUID first = ids.newId();
        UUID second = later.newId();

        assertThat(second.getMostSignificantBits() >>> 16)
                .isGreaterThan(first.getMostSignificantBits() >>> 16);
    }

    @Test
    void idsCreatedInOneMillisecondSortInCreationOrder() {
        UUID previous = ids.newId();
        for (int i = 0; i < 1_000; i++) {
            UUID next = ids.newId();
            assertThat(next).as("an id never sorts before the one created before it").isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void aClockThatStepsBackNeverGoesBackOnAnId() {
        Instant[] now = { NOW };
        UuidV7IdGenerator generator = new UuidV7IdGenerator(() -> now[0]);

        UUID first = generator.newId();
        now[0] = NOW.minusSeconds(5);
        UUID second = generator.newId();

        assertThat(second).as("a step back never hands out an earlier id").isGreaterThan(first);
        assertThat(second.getMostSignificantBits() >>> 16)
                .as("it keeps counting inside the millisecond already used")
                .isEqualTo(first.getMostSignificantBits() >>> 16);
    }

    @Test
    void twoIdsInTheSameMillisecondStillDiffer() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(ids.newId());
        }

        assertThat(seen).hasSize(1_000);
    }
}

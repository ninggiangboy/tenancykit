package dev.ngb.core.spring.runtime;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDs (version 7), which keep B-tree indexes append-mostly.
 *
 * <p>Inside one millisecond the id carries a sequence rather than pure randomness, so two ids created in the same
 * millisecond still sort in creation order. The outbox claims its rows by {@code (next_attempt, id)}, and the events
 * of one transaction must reach a consumer in the order they were published: a version-7 id with random low bits would
 * let a child event overtake its parent.
 */
public class UuidV7IdGenerator implements IdGenerator {

    /** The 12 bits the RFC leaves for sub-millisecond ordering. */
    private static final int SEQUENCE_MASK = 0x0FFF;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private long lastMillis = -1;
    private int sequence;

    /** @param clock the source of the id's timestamp, so a test can pin it and two instances can agree */
    public UuidV7IdGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized UUID newId() {
        long millis = clock.now().toEpochMilli();
        if (millis > lastMillis) {
            lastMillis = millis;
            sequence = 0;
        } else {
            // The same millisecond, or a clock that stepped back: keep counting inside the millisecond that was
            // already handed out, and borrow the next one when the 12 bits run out.
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                lastMillis++;
            }
        }
        long randB = random.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long msb = (lastMillis << 16) | 0x7000L | sequence;
        long lsb = 0x8000000000000000L | randB;
        return new UUID(msb, lsb);
    }
}

package dev.ngb.core.spring.runtime;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered UUIDs (version 7), which keep B-tree indexes append-mostly. */
public class UuidV7IdGenerator implements IdGenerator {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** @param clock the source of the id's timestamp, so a test can pin it and two instances can agree */
    public UuidV7IdGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID newId() {
        long millis = clock.now().toEpochMilli();
        long randA = random.nextLong() & 0x0FFFL;
        long randB = random.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long msb = (millis << 16) | 0x7000L | randA;
        long lsb = 0x8000000000000000L | randB;
        return new UUID(msb, lsb);
    }
}

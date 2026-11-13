package dev.ngb.core.feign;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test moves by hand, so expiry is proven without waiting. */
final class MutableClock implements dev.ngb.core.runtime.Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant now() {
        return now.get();
    }
}

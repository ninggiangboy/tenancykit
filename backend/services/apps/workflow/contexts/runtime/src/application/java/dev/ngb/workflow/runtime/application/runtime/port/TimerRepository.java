package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.timer.Timer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The timer rows: written when a token parks, claimed by the sweep, cancelled when the other side wins. */
public interface TimerRepository {

    void add(Timer timer);

    /** The timers that are due and unfired, claimed for this sweep with {@code FOR UPDATE SKIP LOCKED}. */
    List<Timer> lockDue(Instant now, int limit);

    /** Marks a timer fired when nobody else did; false means another sweep already handled it. */
    boolean markFired(UUID timerId, Instant now);

    /** Closes every open timer of a token, so the branch that won leaves nothing to fire later. */
    int cancelForToken(UUID tokenId, Instant now);
}

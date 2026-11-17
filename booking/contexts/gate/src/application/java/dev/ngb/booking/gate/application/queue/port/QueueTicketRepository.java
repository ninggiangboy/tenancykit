package dev.ngb.booking.gate.application.queue.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The insert-only ticket store and the per-target watermark. */
public interface QueueTicketRepository {

    long nextTicketNo();

    /** @return false when this customer already has an active ticket for the target */
    boolean insert(String targetId, long ticketNo, String customerRef, Instant now);

    Optional<State> state(String targetId);

    void ensureState(String targetId, int admitPerSecond, int admitWindowSeconds, Instant now);

    /** Admits up to {@code limit} WAITING tickets in ticket order, in one statement. */
    int admit(String targetId, int limit, Instant now);

    /** The highest admitted ticket number for the target, and the state's rate and window. */
    Optional<State> admittedState(String targetId, Instant now);

    int expireAdmitted(String targetId, Instant cutoff, Instant now);

    /** Claims an admitted ticket for one hold; false when it is not admitted or its window has passed. */
    boolean markUsedIfAdmitted(String targetId, long ticketNo, Instant cutoff);

    void revertUsed(String targetId, long ticketNo);

    void markLeft(String targetId, long ticketNo);

    long watermark(String targetId);

    List<String> targetsWithWaiting();

    int purgeBefore(Instant cutoff);

    record State(String targetId, long watermark, int admitPerSecond, int admitWindowSeconds, Instant updatedAt) {}
}

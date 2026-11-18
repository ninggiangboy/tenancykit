package dev.ngb.booking.booking.application.waitlist.port;

import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores and locks waiting entries; every statement is narrowed to the current tenant by row-level security. */
public interface WaitlistRepository {

    /** @return false when the customer already waits for this target, which the partial unique index refuses */
    boolean insertIfAbsent(WaitlistEntry entry);

    Optional<WaitlistEntry> lockById(UUID entryId);

    /** The waiting entries of one target, oldest first, locked so one matcher pass owns each. */
    List<WaitlistEntry> lockWaiting(String scope, int limit);

    /** The distinct targets that have a waiting entry, oldest first, for the periodic pass. */
    List<String> waitingScopes(int limit);

    /** How many waiting entries were created before this one for the same target, which is its position. */
    int waitingAhead(WaitlistEntry entry);

    int waitingCount(String scope);

    void update(WaitlistEntry entry);

    Optional<WaitlistEntry> lockByOfferedBooking(UUID bookingId);

    /** Records that a place or range was released, so the worker tries the target without delaying the caller. */
    void wakeup(String scope);

    /** Takes due wake-ups and returns their scopes, so the worker does one pass per distinct target. */
    List<String> claimWakeups(int limit);

    /** The scope of an entry's target: the session for capacity, the offering for an interval range. */
    static String scopeOf(UUID offeringId, UUID sessionId) {
        return sessionId != null ? "session:" + sessionId : "offering:" + offeringId;
    }
}

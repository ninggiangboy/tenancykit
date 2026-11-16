package dev.ngb.loyalty.points.application.expiry.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The lots a scheduled run works through, and the members that hold them.
 *
 * <p>Finding the work never holds a lock: the sweep answers member ids for the run to visit one at a time. Everything a
 * member's run changes happens after that member's row is locked, and the lots are then re-read under their own locks in
 * ascending id, which is the order every flow takes them in.
 */
public interface ExpiryRepository {

    /** The members holding at least one expired lot with points left, earliest due lot first; bounded by {@code limit}. */
    List<UUID> dueMemberIds(Instant now, int limit);

    /** The member's expired lots that still hold points, locked for update in ascending id. */
    List<DueLot> lockDueLots(UUID memberId, Instant now);

    /** Writes a lot off: its remaining points become zero. False when another flow emptied it first. */
    boolean writeOff(UUID lotId);

    /**
     * The members holding an unannounced lot that expires after {@code now} and no later than {@code until}, those due
     * soonest first; bounded by {@code limit}. A lot that has already expired is left to the expiry run, never announced.
     */
    List<UUID> noticeMemberIds(Instant now, Instant until, int limit);

    /** The member's unannounced lots inside the notice window, locked for update in ascending id. */
    List<DueLot> lockNoticeLots(UUID memberId, Instant now, Instant until);

    /** Records that the notice run announced these lots at {@code at}; a lot is announced once, so this is idempotent. */
    void markNoticed(List<UUID> lotIds, Instant at);
}

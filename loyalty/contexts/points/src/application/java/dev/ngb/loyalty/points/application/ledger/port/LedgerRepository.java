package dev.ngb.loyalty.points.application.ledger.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The append-only ledger and the lots a balance is summed from.
 *
 * <p>Nothing here writes a balance: a grant creates a lot, a clawback lowers the remaining points of the lots it
 * consumes, and a shortfall becomes the member's debt. That is why the same rows answer "what can this member spend" at
 * any time without a stored total to keep in step.
 */
public interface LedgerRepository {

    void addEntry(LedgerEntryRow entry);

    void addLot(PointLotRow lot);

    /** Records that one entry consumed part of one lot. */
    void addAllocation(UUID entryId, UUID lotId, long points);

    /** The lots that still hold points and have not expired, ordered by earliest expiry. */
    List<LiveLot> liveLots(UUID memberId, Instant now);

    /** Lowers a lot's remaining points; the caller never asks for more than the lot holds. */
    void consume(UUID lotId, long points);

    /** The sum of the unexpired lots' remaining points, before debt. */
    long liveBalance(UUID memberId, Instant now);
}

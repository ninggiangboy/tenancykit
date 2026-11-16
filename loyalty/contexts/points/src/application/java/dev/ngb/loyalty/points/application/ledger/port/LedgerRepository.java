package dev.ngb.loyalty.points.application.ledger.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Adds points back to a lot. A lot that expired in the meantime takes them back but stays expired: the balance counts
     * only lots that expire in the future, so a restored point into a past lot is never spendable again.
     */
    void restore(UUID lotId, long points);

    /** The entry a redemption wrote, which is what a reversal restores its lots from. */
    Optional<UUID> entryOfRedemption(UUID redemptionId);

    /** What one entry took from each lot, in the order the lots were consumed. */
    List<LotAllocation> allocations(UUID entryId);

    /** The sum of the unexpired lots' remaining points, before debt. */
    long liveBalance(UUID memberId, Instant now);

    /** Every lot of one member that still holds points, expired or not, in ascending id. */
    List<LiveLot> lotsHolding(UUID memberId);

    /**
     * Sets a lot's remaining points to zero, which is how an erasure releases what the member could still have spent.
     *
     * @return false when the lot held nothing by the time it was reached, so no entry is written for it
     */
    boolean zeroLot(UUID lotId);
}

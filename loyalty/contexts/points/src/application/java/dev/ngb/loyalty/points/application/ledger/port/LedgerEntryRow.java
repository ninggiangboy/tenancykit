package dev.ngb.loyalty.points.application.ledger.port;

import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import java.time.Instant;
import java.util.UUID;

/**
 * One ledger entry to append. {@code points} is the magnitude moved and {@code kind} gives the direction.
 *
 * <p>At most one of the references is set: an earn and a clawback name the order they came from, a return adds its own,
 * and a redemption and its reversal name the redemption.
 */
public record LedgerEntryRow(
        UUID id,
        UUID memberId,
        LedgerKind kind,
        long points,
        UUID orderId,
        UUID returnId,
        UUID redemptionId,
        Instant createdAt) {

    public LedgerEntryRow {
        if (points < 0) {
            throw new IllegalArgumentException("an entry's points are a magnitude and never negative");
        }
    }
}

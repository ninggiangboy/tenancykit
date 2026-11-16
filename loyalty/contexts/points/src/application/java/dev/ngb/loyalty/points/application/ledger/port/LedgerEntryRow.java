package dev.ngb.loyalty.points.application.ledger.port;

import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import java.time.Instant;
import java.util.UUID;

/** One ledger entry to append. {@code points} is the magnitude moved and {@code kind} gives the direction. */
public record LedgerEntryRow(
        UUID id, UUID memberId, LedgerKind kind, long points, UUID orderId, UUID returnId, Instant createdAt) {

    public LedgerEntryRow {
        if (points < 0) {
            throw new IllegalArgumentException("an entry's points are a magnitude and never negative");
        }
    }
}

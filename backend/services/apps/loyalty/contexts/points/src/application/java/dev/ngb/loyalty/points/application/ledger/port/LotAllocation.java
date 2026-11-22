package dev.ngb.loyalty.points.application.ledger.port;

import java.util.UUID;

/** What one entry took from one lot, which is exactly what the reversal of that entry adds back. */
public record LotAllocation(UUID lotId, long points) {

    public LotAllocation {
        if (points <= 0) {
            throw new IllegalArgumentException("an allocation moves at least one point");
        }
    }
}

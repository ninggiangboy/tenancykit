package dev.ngb.loyalty.points.application.ledger.query;

import java.time.Instant;
import java.util.UUID;

/** One ledger entry as the read side answers it, with the references that explain it. */
public record LedgerEntryView(
        UUID entryId,
        String kind,
        long points,
        String orderRef,
        String returnRef,
        UUID redemptionId,
        Instant createdAt) {}

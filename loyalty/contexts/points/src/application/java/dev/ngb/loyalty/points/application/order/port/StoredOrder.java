package dev.ngb.loyalty.points.application.order.port;

import dev.ngb.loyalty.points.domain.earn.EarnSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded order with what it is worth: the reference the Tenant sent, the member it belongs to, the fingerprint
 * that makes a replay safe and the snapshot its earn used.
 */
public record StoredOrder(
        UUID id,
        String orderRef,
        UUID memberId,
        String currency,
        Instant occurredAt,
        String storeCode,
        String channel,
        String fingerprint,
        Instant createdAt,
        long grantedPoints,
        long spendAmount,
        EarnSnapshot snapshot) {

    public StoredOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderRef, "orderRef");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        if (grantedPoints < 0 || spendAmount < 0) {
            throw new IllegalArgumentException("what an order earned is never negative");
        }
    }

    /** The order as it is recorded, before any points are applied to it. */
    public StoredOrder asRecorded(EarnSnapshot recorded) {
        return new StoredOrder(id, orderRef, memberId, currency, occurredAt, storeCode, channel, fingerprint, createdAt,
                0L, 0L, recorded);
    }

    public StoredOrder worth(long points, long spend) {
        return new StoredOrder(id, orderRef, memberId, currency, occurredAt, storeCode, channel, fingerprint, createdAt,
                points, spend, snapshot);
    }
}

package dev.ngb.customer.orders.api.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One order as another context reads it: its lines with their cumulative returned figures, the returns recorded
 * against it, and the version that rose with each of them.
 *
 * @param id customer's own id for the order
 * @param orderRef the Tenant's reference for the order, unique per tenant
 * @param currency the three-letter code the amounts are in
 * @param total the order total in minor units, informational and never an earn base
 * @param occurredAt when the order was placed
 * @param storeCode the store the Tenant's own system reported
 * @param channel the channel it reported
 * @param status {@code RECORDED}, {@code PARTIALLY_RETURNED} or {@code RETURNED}
 * @param version 1 when it was recorded and one more for every return
 * @param createdAt when customer recorded it
 */
public record OrderSnapshot(
        UUID id,
        String orderRef,
        String currency,
        long total,
        Instant occurredAt,
        String storeCode,
        String channel,
        String status,
        long version,
        Instant createdAt,
        List<OrderLineSnapshot> lines,
        List<ReturnSnapshot> returns) {

    public OrderSnapshot {
        lines = List.copyOf(lines);
        returns = List.copyOf(returns);
    }
}

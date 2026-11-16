package dev.ngb.crm.orders.application.order.query;

import dev.ngb.crm.orders.domain.order.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One order as a read returns it: its lines with the cumulative returned figures, every return recorded against it,
 * and the version that is both the ETag and the {@code orderVersion} of its webhooks.
 *
 * <p>{@code externalRef} is the Tenant's reference for the customer, added by the read adapter so a caller can match
 * the order to its customer without a second call.
 */
public record OrderView(
        UUID id,
        String orderRef,
        UUID customerId,
        String externalRef,
        String currency,
        long total,
        Instant occurredAt,
        String storeCode,
        String channel,
        OrderStatus status,
        long version,
        Instant createdAt,
        List<OrderLineView> lines,
        List<OrderReturnView> returns) {

    public OrderView {
        lines = List.copyOf(lines);
        returns = List.copyOf(returns);
    }
}

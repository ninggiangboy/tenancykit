package dev.ngb.crm.orders.adapter.order.persistence;

import dev.ngb.crm.orders.api.order.OrderHistory;
import dev.ngb.crm.orders.api.order.OrderLineSnapshot;
import dev.ngb.crm.orders.api.order.OrderSnapshot;
import dev.ngb.crm.orders.api.order.ReturnLineSnapshot;
import dev.ngb.crm.orders.api.order.ReturnSnapshot;
import dev.ngb.crm.orders.application.order.query.OrderReadRepository;
import dev.ngb.crm.orders.application.order.query.OrderView;
import java.util.List;
import java.util.UUID;

/**
 * The orders context's seam for a read model that shows one customer's order history: a bounded, indexed slice of the
 * orders with their lines and returns, newest first.
 *
 * <p>It reads through the context's own read port, so the history a caller receives and the history the listing
 * endpoint pages are the same projection of the same rows — one statement for the orders and one more per child table
 * for the whole slice.
 */
public class JooqOrderHistory implements OrderHistory {

    private final OrderReadRepository orders;

    public JooqOrderHistory(OrderReadRepository orders) {
        this.orders = orders;
    }

    @Override
    public List<OrderSnapshot> latest(UUID customerId, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return orders.findByCustomer(customerId, limit, null).data().stream()
                .map(JooqOrderHistory::snapshot)
                .toList();
    }

    private static OrderSnapshot snapshot(OrderView order) {
        return new OrderSnapshot(
                order.id(),
                order.orderRef(),
                order.currency(),
                order.total(),
                order.occurredAt(),
                order.storeCode(),
                order.channel(),
                order.status().name(),
                order.version(),
                order.createdAt(),
                order.lines().stream()
                        .map(line -> new OrderLineSnapshot(
                                line.lineNo(),
                                line.sku(),
                                line.name(),
                                line.category(),
                                line.quantity(),
                                line.amount(),
                                line.earnEligible(),
                                line.returnedQuantity(),
                                line.returnedAmount()))
                        .toList(),
                order.returns().stream()
                        .map(recorded -> new ReturnSnapshot(
                                recorded.returnRef(),
                                recorded.occurredAt(),
                                recorded.lines().stream()
                                        .map(line -> new ReturnLineSnapshot(
                                                line.lineNo(), line.quantity(), line.amount()))
                                        .toList()))
                        .toList());
    }
}

package dev.ngb.crm.orders.api.order;

import java.util.List;
import java.util.UUID;

/**
 * The orders context's contract for another context that shows a customer's order history: the latest orders of one
 * customer, newest first, bounded by the caller.
 *
 * <p>It is a read and nothing else: nothing can be recorded through it, so a read model cannot change an order by
 * reading it.
 */
public interface OrderHistory {

    /**
     * The customer's latest orders, newest first, each with its lines and the returns recorded against it.
     *
     * @param limit how many to read at most; the caller picks a bound so one read stays fixed in size
     */
    List<OrderSnapshot> latest(UUID customerId, int limit);
}

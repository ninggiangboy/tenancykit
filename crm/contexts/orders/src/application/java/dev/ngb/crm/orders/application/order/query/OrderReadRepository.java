package dev.ngb.crm.orders.application.order.query;

import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads orders straight into the view; the aggregate is never rebuilt for a read.
 *
 * <p>A page of orders carries each order's lines and returns as well, read in one extra statement per table rather than
 * one per order, so a page of a hundred orders stays four queries.
 */
public interface OrderReadRepository {

    Optional<OrderView> findByOrderRef(String orderRef);

    /**
     * One page of a customer's orders, newest first, positioned by a cursor of the previous page.
     *
     * @param limit the page size that was applied
     * @param cursor the {@code next_cursor} of the previous page, or null for the first page
     */
    Page<OrderView> findByCustomer(UUID customerId, int limit, String cursor);
}

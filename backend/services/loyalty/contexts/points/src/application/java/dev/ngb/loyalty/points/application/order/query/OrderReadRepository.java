package dev.ngb.loyalty.points.application.order.query;

import java.time.Instant;
import java.util.Optional;

/** The order read: one recorded order with its lines, its returns and its stored snapshot. */
public interface OrderReadRepository {

    Optional<OrderView> find(String orderRef, Instant now);
}

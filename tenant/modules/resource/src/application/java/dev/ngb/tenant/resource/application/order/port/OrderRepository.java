package dev.ngb.tenant.resource.application.order.port;

import dev.ngb.tenant.resource.domain.order.Order;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for orders. */
public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(UUID id);

    Optional<Order> findByReference(String reference);

    List<Order> findPendingBefore(Instant createdBefore);

    List<Order> findByBillingCustomerId(String billingCustomerId);
}

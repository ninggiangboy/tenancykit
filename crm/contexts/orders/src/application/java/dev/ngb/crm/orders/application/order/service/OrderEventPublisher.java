package dev.ngb.crm.orders.application.order.service;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.customers.api.customer.CustomerRef;
import dev.ngb.crm.orders.domain.order.Order;
import dev.ngb.crm.orders.domain.returns.OrderReturn;

/**
 * Announces what happened to an order: {@code order.recorded} when it was recorded and {@code order.refunded} for each
 * return that changed it.
 *
 * <p>Both carry the order's complete current state plus the {@code orderVersion} that rose with the change, so a
 * Tenant that forwards them needs no other call. {@code order.refunded} also carries the return that caused it.
 *
 * <p>The publisher writes to the outbox inside the transaction of the change, because it is called from the use case
 * that holds it: a rolled-back order leaves no announcement, and an announcement never overtakes the row it describes.
 * The customer's reference — the one fact about the customer an event carries — is resolved here for the same reason,
 * so an erased customer's order can never carry a stale reference.
 */
public class OrderEventPublisher {

    private final EventPublisher events;
    private final IdGenerator ids;
    private final CustomerDirectory customers;
    private final Clock clock;

    public OrderEventPublisher(
            EventPublisher events, IdGenerator ids, CustomerDirectory customers, Clock clock) {
        this.events = events;
        this.ids = ids;
        this.customers = customers;
        this.clock = clock;
    }

    /** Announces an order that did not exist before. */
    public void recorded(Order order) {
        events.publish(order.recorded(ids.newId(), externalRefOf(order)));
    }

    /** Announces a return and the order state it produced, stamped with when the change was recorded. */
    public void refunded(Order order, OrderReturn recorded) {
        events.publish(order.refunded(ids.newId(), externalRefOf(order), recorded, clock.now()));
    }

    private String externalRefOf(Order order) {
        return customers
                .findByCustomerId(order.customerId())
                .map(CustomerRef::externalRef)
                .orElseThrow(() -> new IllegalStateException(
                        "the customer of order " + order.orderRef() + " is not readable in this tenant"));
    }
}

package dev.ngb.crm.customers.application.customer.service;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.crm.customers.domain.customer.Customer;

/**
 * Announces what happened to a customer: {@code customer.created} when it came into existence and
 * {@code customer.erased} when its personal data was erased. Both are written to the outbox inside the transaction of
 * the change, because the publisher is called from the use case that holds it: a rolled-back change leaves no
 * announcement, and an announcement never overtakes the row it describes.
 *
 * <p>The two facts a customer event may carry are the reference the Tenant chose and crm's own id — never an attribute
 * of the person, and an erasure carries the id alone.
 */
public class CustomerEventPublisher {

    private final EventPublisher events;
    private final IdGenerator ids;

    public CustomerEventPublisher(EventPublisher events, IdGenerator ids) {
        this.events = events;
        this.ids = ids;
    }

    public void created(Customer customer) {
        events.publish(customer.created(ids.newId()));
    }

    public void erased(Customer customer) {
        events.publish(customer.erased(ids.newId()));
    }
}

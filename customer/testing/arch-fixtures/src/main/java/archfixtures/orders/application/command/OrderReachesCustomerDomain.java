package archfixtures.orders.application.command;

import archfixtures.customers.domain.Customer;

/** Violates orders-and-activity-reach-customers-through-api: the order reaches a customer's domain type. */
public class OrderReachesCustomerDomain {

    public String ref(Customer customer) {
        return customer.externalRef();
    }
}

package dev.ngb.customer.orders.application.order.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.api.customer.CustomerDirectory;
import dev.ngb.customer.customers.api.customer.CustomerRef;
import dev.ngb.customer.orders.application.order.exception.OrderNotFoundException;
import dev.ngb.customer.orders.application.order.service.OrderRules;

/** Reads the current tenant's orders for the GET handlers. */
public class OrderQueries {

    /** The page size a caller gets when it asks for none, and the largest page a caller may ask for. */
    public static final int DEFAULT_LIMIT = 50;

    public static final int MAX_LIMIT = 200;

    private final UnitOfWork unitOfWork;
    private final OrderReadRepository orders;
    private final CustomerDirectory customers;

    public OrderQueries(UnitOfWork unitOfWork, OrderReadRepository orders, CustomerDirectory customers) {
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.customers = customers;
    }

    /** @throws OrderNotFoundException when the tenant has no order with that reference */
    public OrderView get(String orderRef) {
        String ref = OrderRules.reference(orderRef, OrderRules.MAX_ORDER_REF, "order");
        return unitOfWork.transactional(() -> orders.findByOrderRef(ref).orElseThrow(OrderNotFoundException::new));
    }

    /**
     * A page of the customer's orders, newest first.
     *
     * @throws NotFoundException when the tenant has no customer with that reference
     */
    public Page<OrderView> forCustomer(String externalRef, Integer limit, String cursor) {
        String ref = OrderRules.reference(externalRef, OrderRules.MAX_EXTERNAL_REF, "customer");
        int page = pageSize(limit);
        return unitOfWork.transactional(() -> {
            CustomerRef customer = customers
                    .findByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            return orders.findByCustomer(customer.customerId(), page, cursor);
        });
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("A page size is 1 to " + MAX_LIMIT + ".");
        }
        return limit;
    }
}

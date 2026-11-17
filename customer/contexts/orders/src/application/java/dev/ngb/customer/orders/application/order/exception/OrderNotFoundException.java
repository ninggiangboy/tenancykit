package dev.ngb.customer.orders.application.order.exception;

/** No order with that reference exists in this tenant, so there is nothing to read or to return against. */
public class OrderNotFoundException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "ORDER_NOT_FOUND";

    public OrderNotFoundException() {
        super(CODE, "No order exists here with that reference.");
    }
}

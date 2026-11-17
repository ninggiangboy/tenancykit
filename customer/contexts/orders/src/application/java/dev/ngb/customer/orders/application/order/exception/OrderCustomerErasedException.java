package dev.ngb.customer.orders.application.order.exception;

/**
 * The customer the call names was erased, so the anonymous marker it left behind may not be sold to.
 *
 * <p>It answers {@code 409}: the reference is taken and cannot be reused, and the caller's remedy is a new customer
 * with a new reference.
 */
public class OrderCustomerErasedException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOMER_ERASED";

    public OrderCustomerErasedException(String message) {
        super(CODE, message);
    }
}

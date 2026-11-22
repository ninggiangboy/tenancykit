package dev.ngb.customer.orders.application.order.exception;

/** The body is not an order: a required member is missing, a quantity is not positive or a date is in the future. */
public class InvalidOrderException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidOrderException(String message) {
        super(CODE, message);
    }
}

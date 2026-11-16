package dev.ngb.crm.orders.application.returns.exception;

import dev.ngb.crm.orders.application.order.exception.OrderProblem;

/** The body is not a return: no lines, a line number the order does not have, or a date in the future. */
public class InvalidReturnException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidReturnException(String message) {
        super(CODE, message);
    }
}

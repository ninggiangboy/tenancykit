package dev.ngb.crm.orders.application.returns.exception;

import dev.ngb.crm.orders.application.order.exception.OrderProblem;

/**
 * A return would take back more than was sold: the cumulative returned quantity or amount of a line would exceed the
 * line itself.
 *
 * <p>The order is left exactly as it was, because the check runs before anything is written.
 */
public class ReturnExceedsSaleException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "RETURN_EXCEEDS_SALE";

    public ReturnExceedsSaleException(String message) {
        super(CODE, message);
    }
}

package dev.ngb.customer.orders.application.order.exception;

/** The batch envelope is wrong: it carries no orders, or more orders or lines than one call may hold. */
public class InvalidOrderBatchException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    public static final String BATCH_LIMIT_EXCEEDED = "BATCH_LIMIT_EXCEEDED";

    public InvalidOrderBatchException(String code, String message) {
        super(code, message);
    }
}

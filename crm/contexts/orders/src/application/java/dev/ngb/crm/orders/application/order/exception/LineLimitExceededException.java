package dev.ngb.crm.orders.application.order.exception;

/** One order carries more lines than a call may record, so it is split across calls or sent as a batch. */
public class LineLimitExceededException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "LINE_LIMIT_EXCEEDED";

    public LineLimitExceededException(String message) {
        super(CODE, message);
    }
}

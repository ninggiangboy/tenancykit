package dev.ngb.crm.customers.application.customer.exception;

/** The batch request itself is wrong, as opposed to one of its items: it carries no items, or more than it may. */
public class InvalidBatchException extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    /** The envelope is malformed or empty. */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** The envelope holds more items than one call may apply. */
    public static final String BATCH_LIMIT_EXCEEDED = "BATCH_LIMIT_EXCEEDED";

    public InvalidBatchException(String code, String message) {
        super(code, message);
    }
}

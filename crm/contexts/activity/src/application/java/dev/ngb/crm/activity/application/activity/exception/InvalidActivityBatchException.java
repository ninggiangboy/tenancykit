package dev.ngb.crm.activity.application.activity.exception;

/** The batch envelope is wrong: it carries no activities, or more of them than one call may hold. */
public class InvalidActivityBatchException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    public static final String BATCH_LIMIT_EXCEEDED = "BATCH_LIMIT_EXCEEDED";

    public InvalidActivityBatchException(String code, String message) {
        super(code, message);
    }
}

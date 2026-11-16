package dev.ngb.crm.activity.application.activity.exception;

/** The body is not an activity: the type does not match the documented shape, or a required member is missing. */
public class InvalidActivityException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidActivityException(String message) {
        super(CODE, message);
    }
}

package dev.ngb.crm.activity.application.activity.exception;

/** The payload is not a JSON object, or it is larger than one activity may carry. */
public class ActivityPayloadTooLargeException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "PAYLOAD_TOO_LARGE";

    public ActivityPayloadTooLargeException(String message) {
        super(CODE, message);
    }
}

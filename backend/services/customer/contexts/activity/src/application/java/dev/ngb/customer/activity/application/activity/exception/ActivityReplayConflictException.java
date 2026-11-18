package dev.ngb.customer.activity.application.activity.exception;

/** The same activityRef was sent before with another body; the stored activity is kept and the call is refused. */
public class ActivityReplayConflictException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "IDEMPOTENCY_CONFLICT";

    public ActivityReplayConflictException(String message) {
        super(CODE, message);
    }
}

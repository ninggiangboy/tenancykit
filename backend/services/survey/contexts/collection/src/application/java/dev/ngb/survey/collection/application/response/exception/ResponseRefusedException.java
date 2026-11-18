package dev.ngb.survey.collection.application.response.exception;

/**
 * A submission is refused for a reason about the survey rather than the answers: its cap is spent, its window closed,
 * or the version this visit opened no longer exists.
 *
 * <p>Each reason answers a distinct code a client can branch on, and none of them quotes anything the respondent sent.
 */
public class ResponseRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The survey has accepted as many completed responses as its cap allows. */
        CAP_REACHED,
        /** The survey's window closed before this submission arrived. */
        WINDOW_CLOSED,
        /** The version this visit pinned is no longer readable, which a version being immutable should prevent. */
        VERSION_GONE
    }

    private final transient Reason reason;

    public ResponseRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

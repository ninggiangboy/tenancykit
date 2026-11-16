package dev.ngb.survey.collection.application.visit.exception;

/**
 * A personal link that already has a completed response cannot be opened again.
 *
 * <p>It is a conflict rather than a not-found because the link is valid and the recipient should be told why: the
 * survey is done for this link. The distinct answer cannot leak anything a holder of the link does not already know.
 */
public class AlreadyCompletedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlreadyCompletedException() {
        super("this personal link has already completed the survey");
    }
}

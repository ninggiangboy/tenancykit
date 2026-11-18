package dev.ngb.survey.collection.application.response.exception;

/**
 * A visit whose response already completed cannot go back to a draft, and cannot complete a second time.
 *
 * <p>It is a conflict rather than a refusal: the caller's credential is valid and the answer tells it why nothing
 * happened, which is what a browser that reloaded a submitted page needs.
 */
public class AlreadySubmittedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlreadySubmittedException() {
        super("this visit already submitted its response");
    }
}

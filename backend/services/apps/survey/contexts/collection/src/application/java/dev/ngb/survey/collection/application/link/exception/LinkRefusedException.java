package dev.ngb.survey.collection.application.link.exception;

/**
 * A link cannot be issued as asked: a public link already names this source, or the survey is not open.
 *
 * <p>The message never echoes the source or the recipient reference: it is the tenant's own data, and the pointer
 * already names what conflicted.
 */
public class LinkRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** A public link for this source already exists; the source is a report's identity. */
        SOURCE_TAKEN,
        /** The survey is not OPEN, so a link would open nothing. */
        SURVEY_NOT_OPEN,
        /** A personal bulk names the same recipient reference twice. */
        DUPLICATE_REFERENCE
    }

    private final transient Reason reason;

    public LinkRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

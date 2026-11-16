package dev.ngb.survey.shared;

/**
 * A conditional endpoint was called without the {@code If-Match} it requires.
 *
 * <p>Distinct from a stale tag: {@code 412} means the caller compared with an older state, {@code 428} means it
 * compared with nothing. Editing a draft without one would silently overwrite another editor's work.
 */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException() {
        super("the endpoint requires an If-Match header");
    }
}

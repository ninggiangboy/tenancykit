package dev.ngb.workflow.connections.application.connection.exception;

/** A connection change without {@code If-Match} would silently overwrite another caller's edit. */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}

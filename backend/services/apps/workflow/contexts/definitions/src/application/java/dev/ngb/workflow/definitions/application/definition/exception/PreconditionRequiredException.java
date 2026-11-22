package dev.ngb.workflow.definitions.application.definition.exception;

/**
 * A conditional change without {@code If-Match} says nothing about the state it was decided on, so it would silently
 * overwrite another caller's. The endpoint asks for the version instead of guessing, and answers 428.
 */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}

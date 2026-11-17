package dev.ngb.workflow.tasks.application.task.exception;

/** A conditional change without its version says nothing about the state it was decided on. */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}

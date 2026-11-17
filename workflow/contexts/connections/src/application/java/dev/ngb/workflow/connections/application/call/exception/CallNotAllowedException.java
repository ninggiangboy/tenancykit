package dev.ngb.workflow.connections.application.call.exception;

/** The call cannot be made as asked: an unknown app, a connection that does not allow the host, or no secret. */
public class CallNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CallNotAllowedException(String reason) {
        super(reason);
    }
}

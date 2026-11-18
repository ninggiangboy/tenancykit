package dev.ngb.customer.webhook.application.endpoint.exception;

/**
 * The caller sent no {@code If-Match} on a conditional change. An endpoint's update and delete are conditional, so two
 * callers cannot overwrite each other's change without seeing the version they are changing.
 */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}

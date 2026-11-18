package dev.ngb.notification.dispatch.application.notification.exception;

/**
 * The command this service built does not satisfy {@code email.send.schema.json}.
 *
 * <p>The message is fixed on purpose: the validator's own message can embed the offending value, and an address must
 * not travel into {@code outbox.last_error} or the relay's log.
 */
public class InvalidEmailCommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEmailCommandException() {
        super("the email.send command failed its contract");
    }
}

package dev.ngb.mailer.email.application.email.exception;

/**
 * No sender could be resolved for a delivery, or the reply-to the producer asked for is not allowed. The delivery is
 * rejected rather than retried: a configuration mistake does not fix itself, and retrying only burns quota while
 * hiding the cause.
 */
public class UnresolvableSenderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnresolvableSenderException(String message) {
        super(message);
    }

    public UnresolvableSenderException(String message, Throwable cause) {
        super(message, cause);
    }
}

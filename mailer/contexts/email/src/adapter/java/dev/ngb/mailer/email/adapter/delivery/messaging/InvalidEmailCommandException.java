package dev.ngb.mailer.email.adapter.delivery.messaging;

/**
 * The message is not a valid {@code email.send}. Nothing has been written when this is thrown: the validator runs before
 * the inbox row and before any ledger row, so a rejected command leaves no trace except its dead letter.
 *
 * <p>It is a {@link RuntimeException} because {@code InboundEventHandler.handle} declares no checked exception. That
 * means the dispatcher's retry template sees it and retries it, and after its attempts the message is rejected without
 * requeue and lands in {@code mailer.commands.dlq}. The retries are accepted deliberately: a schema failure is cheap
 * because the whole unit of work rolled back, and making mailer's listener container skip retries would be
 * mailer-specific wiring for no gain.
 */
public class InvalidEmailCommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEmailCommandException(String message) {
        super(message);
    }

    public InvalidEmailCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}

package dev.ngb.mailer.email.application.email.port;

/**
 * Publishes the terminal outcome of a delivery so the producer can report {@code SENT} or {@code FAILED}.
 *
 * <p>The call happens inside the transaction that records the outcome, so a crash cannot record an outcome and lose
 * its event; the adapter writes to the transactional outbox and the relay publishes after the commit. A transient
 * attempt is never published.
 */
public interface DeliveryResultPublisher {

    /** Records the outcome in the caller's transaction. It never calls the broker synchronously. */
    void publish(DeliveryResult result);
}

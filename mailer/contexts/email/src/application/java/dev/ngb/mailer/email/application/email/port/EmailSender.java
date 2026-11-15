package dev.ngb.mailer.email.application.email.port;

/**
 * The one seam between the application and a delivery transport. Everything that is not a send — the ledger, the
 * inbox, backoff, the lease — stays above it, so both adapters inherit that behaviour unchanged.
 *
 * <p>The result is a closed three-way answer because that is exactly the decision the ledger needs, and naming a
 * transport's exception here would leak its vocabulary into the application layer.
 */
public interface EmailSender {

    SendOutcome send(OutgoingEmail email);
}

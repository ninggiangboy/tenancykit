package dev.ngb.mailer.email.application.email.port;

/**
 * Marks the transport-specific credential handle a {@link ResolvedSender} carries. The application layer never
 * interprets it — it only passes it back to the sender — which is what keeps an AWS SDK type out of a layer that must
 * stay plain Java.
 */
public interface SenderCredentials {}

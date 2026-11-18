package dev.ngb.mailer.email.application.email.port;

import java.util.Objects;

/**
 * Everything a transport needs to send one email for one tenant, resolved server-side.
 *
 * @param region the SES region; identities exist per account <em>and</em> region, so this always comes from the
 *     credential the message will be sent with, never from a global default
 * @param fromAddress the verified address, never the producer's {@code fromKey}
 * @param fromName the display name the producer chose
 * @param replyTo the checked reply-to, or {@code null} to let the client use the from address
 * @param identity a stable key for this resolution, so a transport can cache one client per credential instead of
 *     building one per send
 * @param credentials the opaque handle the transport interprets
 */
public record ResolvedSender(
        String region, String fromAddress, String fromName, String replyTo, String identity, SenderCredentials credentials) {

    public ResolvedSender {
        Objects.requireNonNull(fromAddress, "fromAddress");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(credentials, "credentials");
    }
}

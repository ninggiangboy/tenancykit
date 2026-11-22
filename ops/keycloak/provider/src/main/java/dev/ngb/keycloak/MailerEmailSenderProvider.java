package dev.ngb.keycloak;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.UserModel;

/**
 * Sends the mail Keycloak has already rendered by handing it to the suite's mailer, the only service that calls AWS.
 *
 * <p>The command is scoped to the user's tenant, so the user must carry the {@code tenant_id} attribute Platform writes
 * when it creates the identity. Any failure is an {@link EmailException}: Keycloak logs it and the reset page shows the
 * same message it shows for an address with no account, so a broker outage does not reveal which addresses exist.
 */
final class MailerEmailSenderProvider implements EmailSenderProvider {

    /** The user attribute holding the id of the tenant the user belongs to. */
    static final String TENANT_ID_ATTRIBUTE = "tenant_id";

    private final MailerSink sink;
    private final String fromKey;

    MailerEmailSenderProvider(MailerSink sink, String fromKey) {
        this.sink = sink;
        this.fromKey = fromKey;
    }

    @Override
    public void send(Map<String, String> config, UserModel user, String subject, String textBody, String htmlBody)
            throws EmailException {
        String to = user.getEmail();
        if (to == null || to.isBlank()) {
            throw new EmailException("user " + user.getId() + " has no email address");
        }
        UUID tenantId = tenantOf(user);
        try {
            sink.publish(MailerCommand.of(tenantId, to, fromKey, subject, textBody, htmlBody, Instant.now()));
        } catch (IOException e) {
            throw new EmailException("the mailer command for user " + user.getId() + " was not published", e);
        }
    }

    /** The mailer needs a tenant for every command; a bare address has none, so only the user overload sends. */
    @Override
    public void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody)
            throws EmailException {
        throw new EmailException("the suite mailer sends only to a user that belongs to a tenant, not to a bare address");
    }

    /** Nothing to check: the sender has no per-realm settings, and the server-level ones are read when it starts. */
    @Override
    public void validate(Map<String, String> config) {}

    @Override
    public void close() {}

    private static UUID tenantOf(UserModel user) throws EmailException {
        String value = user.getFirstAttribute(TENANT_ID_ATTRIBUTE);
        if (value == null || value.isBlank()) {
            throw new EmailException("user " + user.getId() + " has no " + TENANT_ID_ATTRIBUTE + " attribute");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new EmailException("user " + user.getId() + " has a malformed " + TENANT_ID_ATTRIBUTE, e);
        }
    }
}

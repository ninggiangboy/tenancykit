package dev.ngb.mailroom.sending.application.email.port;

import dev.ngb.mailroom.sending.domain.email.Email;
import java.util.Map;

/**
 * Builds the {@code email.send} command from a stored email and validates it against the packaged contract before the
 * outbox row exists, so a producer drift fails here instead of in mailer.
 */
public interface EmailCommandFactory {

    EmailCommand create(Email email, Map<String, String> headers);

    /** @param aggregate the outbox aggregate, {@code email/<id>} */
    record EmailCommand(String aggregate, Map<String, Object> payload) {}
}

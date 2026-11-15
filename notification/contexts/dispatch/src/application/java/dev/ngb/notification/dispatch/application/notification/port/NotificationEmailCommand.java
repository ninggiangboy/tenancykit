package dev.ngb.notification.dispatch.application.notification.port;

import java.util.Map;
import java.util.UUID;

/**
 * One validated {@code email.send} command.
 *
 * @param aggregate the outbox aggregate, {@code notification/<dispatchId>}
 * @param payload the command's {@code data} member, exactly as it was validated
 */
public record NotificationEmailCommand(UUID requestId, String aggregate, Map<String, Object> payload) {

    public NotificationEmailCommand {
        payload = Map.copyOf(payload);
    }
}

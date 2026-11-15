package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.core.id.TenantId;
import java.util.UUID;

/**
 * Builds the {@code email.send} payload and validates it against the packaged contract, so a producer drift fails in
 * this service rather than in mailer's dead-letter queue.
 */
public interface EmailCommandFactory {

    NotificationEmailCommand create(EmailCommandRequest request);

    /**
     * @param dispatchId the dispatch row's id, which is also {@code requestId}: a relay retry, a redelivered event or
     *     a manual resend all resolve to the same mailer rows
     */
    record EmailCommandRequest(
            UUID dispatchId,
            TenantId tenantId,
            String tenantName,
            String recipient,
            String fromKey,
            String subject,
            String html,
            String text) {}
}

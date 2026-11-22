package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.notification.dispatch.application.notification.port.EmailCommandFactory;
import dev.ngb.notification.dispatch.application.notification.port.EmailCommandFactory.EmailCommandRequest;
import dev.ngb.notification.dispatch.application.notification.port.EmailDispatchRepository;
import dev.ngb.notification.dispatch.application.notification.port.NotificationEmailCommand;
import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import dev.ngb.notification.dispatch.domain.notification.EmailDispatch;
import dev.ngb.notification.dispatch.domain.notification.EmailDispatchStatus;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.RenderedMessage;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.platform.api.replica.UserView;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The email half of one accepted notification.
 *
 * <p>It writes the dispatch ledger row and — when the message is publishable — an outbox row in the same transaction
 * that stored the in-app notification, so the two channels either both exist or neither does. The outbox is not
 * optional: publishing from the handler would send an email for a transaction that rolls back, and a broker outage
 * would lose it. The relay, not this use case, calls the broker.
 *
 * <p>No coalescing window: a role edit emits one {@code user-access.changed} per affected user, so a burst is one email
 * per *different* user, not many to one. The unique dispatch key is the only dedupe, and a racy window that silently
 * drops mail is worse than the rare duplicate.
 */
public class EmailDispatchUseCase {

    public static final String EMAIL_SEND = "email.send";

    private final UnitOfWork unitOfWork;
    private final EmailDispatchRepository dispatches;
    private final EmailCommandFactory commands;
    private final EventPublisher publisher;
    private final Clock clock;
    private final IdGenerator ids;
    private final String fromKey;

    public EmailDispatchUseCase(
            UnitOfWork unitOfWork,
            EmailDispatchRepository dispatches,
            EmailCommandFactory commands,
            EventPublisher publisher,
            Clock clock,
            IdGenerator ids,
            String fromKey) {
        this.unitOfWork = unitOfWork;
        this.dispatches = dispatches;
        this.commands = commands;
        this.publisher = publisher;
        this.clock = clock;
        this.ids = ids;
        this.fromKey = fromKey;
    }

    /**
     * Records one recipient's email outcome.
     *
     * @return true when a command was queued, false when the row was skipped or already existed
     */
    public boolean dispatch(
            Change change,
            RenderedMessage message,
            UserView recipient,
            PreferenceView preference,
            TenantView tenant) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            EmailDispatchStatus status = statusFor(message.templateKey(), recipient, preference);
            UUID dispatchId = ids.newId();
            String reason = reasonFor(status);
            EmailDispatch dispatch = new EmailDispatch(
                    dispatchId,
                    change.tenantId(),
                    change.eventId(),
                    typeOf(message),
                    recipient.userId(),
                    recipient.email(),
                    dispatchId.toString(),
                    fromKey,
                    message.templateKey().value(),
                    message.templateVersion(),
                    message.locale(),
                    message.subject(),
                    status,
                    reason,
                    now,
                    status == EmailDispatchStatus.PUBLISHED ? now : null);

            if (!dispatches.insertIfAbsent(dispatch) || status != EmailDispatchStatus.PUBLISHED) {
                return false;
            }
            NotificationEmailCommand command = commands.create(new EmailCommandRequest(
                    dispatchId,
                    change.tenantId(),
                    tenant.name(),
                    recipient.email(),
                    fromKey,
                    message.subject(),
                    message.html(),
                    message.text()));
            publisher.publish(new EmailSendCommand(
                    dispatchId, change.tenantId(), EMAIL_SEND, command.aggregate(), now, command.payload()));
            return true;
        });
    }

    private static EmailDispatchStatus statusFor(
            dev.ngb.notification.shared.TemplateKey templateKey, UserView recipient, PreferenceView preference) {
        NotificationType type = typeOf(templateKey);
        // The order is the plan's: muted, no address, inactive.
        if (preference.muted(type)) {
            return EmailDispatchStatus.SUPPRESSED_MUTED;
        }
        if (recipient.email() == null || recipient.email().isBlank()) {
            return EmailDispatchStatus.SKIPPED_NO_ADDRESS;
        }
        if (!recipient.active()) {
            return EmailDispatchStatus.SKIPPED_INACTIVE;
        }
        return EmailDispatchStatus.PUBLISHED;
    }

    private static String reasonFor(EmailDispatchStatus status) {
        return switch (status) {
            case SUPPRESSED_MUTED -> "the user muted this notification type";
            case SKIPPED_INACTIVE -> "the user's account is disabled; the in-app notification is kept";
            case SKIPPED_NO_ADDRESS -> "the replica has no usable email address for the user";
            default -> null;
        };
    }

    /** The template key is the type's own key; resolve it back without threading the type twice. */
    private static NotificationType typeOf(RenderedMessage message) {
        return typeOf(message.templateKey());
    }

    private static NotificationType typeOf(dev.ngb.notification.shared.TemplateKey key) {
        for (NotificationType type : NotificationType.values()) {
            if (type.templateKey().equals(key.value())) {
                return type;
            }
        }
        throw new IllegalArgumentException("no notification type owns the template key " + key.value());
    }

    private record EmailSendCommand(
            UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt,
            Map<String, Object> payload)
            implements DomainEvent {}
}

package dev.ngb.email.sending.application.email.command;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.util.Text;
import dev.ngb.email.sending.application.email.port.EmailEventRepository;
import dev.ngb.email.sending.application.email.port.EmailRepository;
import dev.ngb.email.sending.domain.email.DeliveryOutcome;
import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.sending.domain.email.EmailEvent;
import dev.ngb.email.sending.domain.email.EmailEventType;
import dev.ngb.email.sending.domain.email.EmailStatus;
import java.util.Optional;

/**
 * Applies mailer's terminal outcome.
 *
 * <p>It matches by {@code (tenant, requestId)}, maps the known statuses and ignores the rest, and keeps the newest
 * {@code completedAt}: an out-of-order replay changes nothing. A request id this app does not know is dropped rather
 * than retried — the email may have been purged, and redelivery would never fix it.
 */
public class ApplyDeliveryResultUseCase {

    private static final int MAX_REASON = 500;

    private final UnitOfWork unitOfWork;
    private final EmailRepository emails;
    private final EmailEventRepository events;
    private final IdGenerator ids;

    public ApplyDeliveryResultUseCase(
            UnitOfWork unitOfWork,
            EmailRepository emails,
            EmailEventRepository events,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.emails = emails;
        this.events = events;
        this.ids = ids;
    }

    public boolean execute(DeliveryOutcome outcome) {
        EmailStatus status = outcome.succeeded()
                ? EmailStatus.SENT
                : outcome.dead() ? EmailStatus.FAILED : null;
        if (status == null) {
            return false;
        }
        return unitOfWork.transactional(() -> {
            Optional<Email> existing = emails.findByRequestId(outcome.requestId());
            if (existing.isEmpty()) {
                return false;
            }
            Email email = existing.get();
            String reason = status == EmailStatus.FAILED ? Text.clip(outcome.lastError(), MAX_REASON) : null;
            boolean applied = emails.applyDelivery(outcome.requestId(), status, reason, outcome.completedAt());
            if (applied) {
                events.append(new EmailEvent(
                        ids.newId(),
                        email.tenantId(),
                        email.id(),
                        email.campaignId(),
                        status == EmailStatus.SENT ? EmailEventType.SENT : EmailEventType.FAILED,
                        null,
                        outcome.completedAt()));
            }
            return applied;
        });
    }
}

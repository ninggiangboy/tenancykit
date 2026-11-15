package dev.ngb.mailroom.sending.application.tracking.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.audience.api.unsubscribe.UnsubscribeAddress;
import dev.ngb.mailroom.sending.application.email.port.EmailEventRepository;
import dev.ngb.mailroom.sending.application.email.port.EmailRepository;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.domain.email.Email;
import dev.ngb.mailroom.sending.domain.email.EmailEvent;
import dev.ngb.mailroom.sending.domain.email.EmailEventType;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import java.util.Optional;

/**
 * The RFC 8058 one-click unsubscribe: an idempotent MARKETING suppression and a receiver status flip, plus one
 * {@code UNSUBSCRIBED} event the first time. It never stops transactional mail.
 */
public class UnsubscribeUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final TrackingTokens tokens;
    private final EmailRepository emails;
    private final EmailEventRepository events;
    private final UnsubscribeAddress unsubscribeAddress;
    private final IdGenerator ids;
    private final Clock clock;

    public UnsubscribeUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailRepository emails,
            EmailEventRepository events,
            UnsubscribeAddress unsubscribeAddress,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.tokens = tokens;
        this.emails = emails;
        this.events = events;
        this.unsubscribeAddress = unsubscribeAddress;
        this.ids = ids;
        this.clock = clock;
    }

    public void unsubscribe(String token) {
        tokens.verify(token, TrackingAction.UNSUBSCRIBE).ifPresent(claims -> tenantContext.runAs(
                RequestScope.tenant(claims.tenantId()), () -> unitOfWork.transactional(() -> {
                    Optional<Email> email = emails.findById(claims.emailId());
                    if (email.isEmpty()) {
                        return null;
                    }
                    unsubscribeAddress.unsubscribe(claims.tenantId(), email.get().toAddress());
                    if (!events.exists(email.get().id(), EmailEventType.UNSUBSCRIBED)) {
                        events.append(new EmailEvent(
                                ids.newId(), claims.tenantId(), email.get().id(), email.get().campaignId(),
                                EmailEventType.UNSUBSCRIBED, null, clock.now()));
                    }
                    return null;
                })));
    }
}

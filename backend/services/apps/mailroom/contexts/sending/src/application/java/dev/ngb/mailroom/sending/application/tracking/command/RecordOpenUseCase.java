package dev.ngb.mailroom.sending.application.tracking.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.sending.application.email.port.EmailEventRepository;
import dev.ngb.mailroom.sending.application.email.port.EmailRepository;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.domain.email.Email;
import dev.ngb.mailroom.sending.domain.email.EmailEvent;
import dev.ngb.mailroom.sending.domain.email.EmailEventType;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import java.time.Instant;
import java.util.UUID;

/**
 * Records an open. It is insert-only and bounded: an email may record at most sixty opens a minute, so a scanner
 * cannot inflate the count without limit. The tenant scope comes from the verified token, never from the host.
 */
public class RecordOpenUseCase {

    static final int EVENTS_PER_MINUTE = 60;

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final TrackingTokens tokens;
    private final EmailRepository emails;
    private final EmailEventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordOpenUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailRepository emails,
            EmailEventRepository events,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.tokens = tokens;
        this.emails = emails;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public void record(String token) {
        tokens.verify(token, TrackingAction.OPEN).ifPresent(claims -> tenantContext.runAs(
                RequestScope.tenant(claims.tenantId()), () -> unitOfWork.transactional(() -> {
                    Instant now = clock.now();
                    if (events.countSince(claims.emailId(), EmailEventType.OPENED, now.minusSeconds(60))
                            >= EVENTS_PER_MINUTE) {
                        return null;
                    }
                    UUID campaignId = emails.findById(claims.emailId())
                            .map(Email::campaignId)
                            .orElse(null);
                    events.append(new EmailEvent(
                            ids.newId(), claims.tenantId(), claims.emailId(), campaignId,
                            EmailEventType.OPENED, null, now));
                    return null;
                })));
    }
}

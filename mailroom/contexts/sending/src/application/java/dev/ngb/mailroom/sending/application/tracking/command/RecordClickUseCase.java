package dev.ngb.mailroom.sending.application.tracking.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.sending.application.email.port.EmailEventRepository;
import dev.ngb.mailroom.sending.application.email.port.EmailRepository;
import dev.ngb.mailroom.sending.application.tracking.port.EmailLinkRepository;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.domain.email.Email;
import dev.ngb.mailroom.sending.domain.email.EmailEvent;
import dev.ngb.mailroom.sending.domain.email.EmailEventType;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a click to the URL recorded at render time. It never accepts a URL from the request, so the suite domain
 * cannot become an open redirect; a token whose link was purged answers empty and the edge shows "link expired".
 */
public class RecordClickUseCase {

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final TrackingTokens tokens;
    private final EmailLinkRepository links;
    private final EmailRepository emails;
    private final EmailEventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordClickUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TrackingTokens tokens,
            EmailLinkRepository links,
            EmailRepository emails,
            EmailEventRepository events,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.tokens = tokens;
        this.links = links;
        this.emails = emails;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public Optional<String> click(String token) {
        return tokens.verify(token, TrackingAction.CLICK).flatMap(claims -> tenantContext.callAs(
                RequestScope.tenant(claims.tenantId()), () -> unitOfWork.transactional(() -> {
                    Optional<String> url = links.findUrl(claims.emailId(), claims.linkId());
                    if (url.isEmpty()) {
                        return Optional.empty();
                    }
                    Instant now = clock.now();
                    if (events.countSince(claims.emailId(), EmailEventType.CLICKED, now.minusSeconds(60))
                            < RecordOpenUseCase.EVENTS_PER_MINUTE) {
                        UUID campaignId = emails.findById(claims.emailId())
                                .map(Email::campaignId)
                                .orElse(null);
                        events.append(new EmailEvent(
                                ids.newId(), claims.tenantId(), claims.emailId(), campaignId,
                                EmailEventType.CLICKED, claims.linkId(), now));
                    }
                    return url;
                })));
    }
}

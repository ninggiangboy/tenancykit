package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.shared.FromKey;

/**
 * Turns a producer's configuration key into the address, region and credentials a send actually uses.
 *
 * <p>There is exactly one of these for the whole service, and it grows a mode rather than gaining a sibling: the
 * delivery job must be unaware of which mode it is in.
 */
public interface SenderResolver {

    /**
     * @param replyTo the producer's reply-to, or {@code null}. An address outside the resolved sender's allowlist is
     *     refused, because an arbitrary reply-to turns a DKIM-valid suite email into business-email compromise.
     * @throws dev.ngb.mailer.email.application.email.exception.UnresolvableSenderException when no sender can be
     *     resolved, or the reply-to is not allowed
     */
    ResolvedSender resolve(TenantId tenantId, FromKey fromKey, String replyTo);
}

package dev.ngb.email.sending.application.tracking.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.sending.domain.tracking.TrackingAction;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the one token that authorises one action on one email. A token carries no expiry — mail is read
 * years later — and an invalid one is indistinguishable from a valid one at the HTTP edge.
 */
public interface TrackingTokens {

    String sign(TenantId tenantId, UUID emailId, int linkId, TrackingAction action);

    /** Empty for a tampered, truncated or wrong-action token; never throws on hostile input. */
    Optional<Claims> verify(String token, TrackingAction expectedAction);

    record Claims(TenantId tenantId, UUID emailId, int linkId) {}
}

package dev.ngb.booking.gate.domain.token;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The verified content of a customer token. The tenant is carried so a token minted for one host is refused on another,
 * even when both hosts resolve to a real tenant.
 */
public record CustomerClaims(
        TenantId tenantId,
        String customerRef,
        Set<GateScope> scopes,
        UUID offeringId,
        Instant expiresAt,
        String keyId) {

    public boolean has(GateScope scope) {
        return scopes.contains(scope);
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** A token bound to one offering may only act on that offering; an unbound token may act on any. */
    public boolean allowsOffering(UUID offering) {
        return offeringId == null || offeringId.equals(offering);
    }
}

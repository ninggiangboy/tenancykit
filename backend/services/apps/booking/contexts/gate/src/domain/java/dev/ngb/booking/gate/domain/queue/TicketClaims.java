package dev.ngb.booking.gate.domain.queue;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/** What a ticket token proves, verified before any ticket row is read. */
public record TicketClaims(
        TenantId tenantId,
        String targetId,
        UUID offeringId,
        long ticketNo,
        String customerRef,
        Instant expiresAt,
        String keyId) {

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}

package dev.ngb.email.sending.domain.email;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One append-only fact about an email. It never carries an address, an IP or a user agent. */
public record EmailEvent(
        UUID id,
        TenantId tenantId,
        UUID emailId,
        UUID campaignId,
        EmailEventType type,
        Integer linkId,
        Instant occurredAt) {

    public EmailEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(emailId, "emailId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}

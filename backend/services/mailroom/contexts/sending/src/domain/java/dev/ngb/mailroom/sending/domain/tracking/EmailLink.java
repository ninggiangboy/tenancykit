package dev.ngb.mailroom.sending.domain.tracking;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A link recorded at render time. The click endpoint looks it up by {@code (email, position)} and never accepts a URL
 * parameter, so the suite domain cannot become an open redirect.
 */
public record EmailLink(
        UUID id, TenantId tenantId, UUID emailId, int position, String url, Instant createdAt) {

    public EmailLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(emailId, "emailId");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(createdAt, "createdAt");
        if (position < 0) {
            throw new IllegalArgumentException("a link position is not negative");
        }
    }
}

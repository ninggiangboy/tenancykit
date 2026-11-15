package dev.ngb.email.audience.domain.suppression;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * A suppressed address. It outlives the receiver on purpose: erasing a customer removes their data but keeps this
 * row, so an address that is deleted and later re-imported is still not mailed.
 */
public record Suppression(
        TenantId tenantId,
        String emailNormalized,
        SuppressionScope scope,
        SuppressionReason reason,
        Instant createdAt) {

    public Suppression {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(emailNormalized, "emailNormalized");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

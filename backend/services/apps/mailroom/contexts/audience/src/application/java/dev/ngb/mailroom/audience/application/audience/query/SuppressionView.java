package dev.ngb.mailroom.audience.application.audience.query;

import dev.ngb.mailroom.audience.domain.suppression.SuppressionReason;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionScope;
import java.time.Instant;

/** One suppressed address. */
public record SuppressionView(
        String email, SuppressionScope scope, SuppressionReason reason, Instant createdAt) {}

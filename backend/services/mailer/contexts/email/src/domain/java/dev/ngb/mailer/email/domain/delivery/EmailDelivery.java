package dev.ngb.mailer.email.domain.delivery;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.shared.FromKey;
import dev.ngb.mailer.shared.Recipient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One (tenant, request, recipient) delivery: the ledger row that answers "was this email sent, how many times was it
 * tried, and why did it fail".
 *
 * <p>There is one row per recipient rather than one per request, so a single bad address cannot hide the successful
 * deliveries beside it, and a rejection of one recipient cannot roll back the others.
 *
 * <p>Only the ledger fields are here. The body lives in its own table so it can be purged on a short schedule while
 * the ledger row survives, which is what leaves personal data bounded without losing the answer.
 */
public record EmailDelivery(
        UUID id,
        TenantId tenantId,
        String requestId,
        Recipient recipient,
        FromKey fromKey,
        String subject,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant claimedUntil,
        String providerMessageId,
        String lastError,
        Instant createdAt,
        Instant completedAt) {

    public EmailDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        // The terminal states always carry a completion time, and the transient ones never do. The database says
        // the same thing in a check constraint; this keeps the object from being built into a state it would reject.
        boolean terminal = status == DeliveryStatus.SUCCEEDED || status == DeliveryStatus.DEAD;
        if (terminal != (completedAt != null)) {
            throw new IllegalArgumentException("a terminal delivery carries a completion time and a live one does not");
        }
    }

    /** A delivery that has never been attempted. */
    public static EmailDelivery pending(
            UUID id, TenantId tenantId, String requestId, Recipient recipient, FromKey fromKey, String subject, Instant at) {
        return new EmailDelivery(
                id, tenantId, requestId, recipient, fromKey, subject, DeliveryStatus.PENDING, 0, at, null, null, null, at, null);
    }

    /** True when this delivery has already been answered and a repeated request must not create a second email. */
    public boolean isTerminal() {
        return status == DeliveryStatus.SUCCEEDED || status == DeliveryStatus.DEAD;
    }

    /** True when the producer's repeated request must bring this delivery back instead of silently dropping it. */
    public boolean isDead() {
        return status == DeliveryStatus.DEAD;
    }
}

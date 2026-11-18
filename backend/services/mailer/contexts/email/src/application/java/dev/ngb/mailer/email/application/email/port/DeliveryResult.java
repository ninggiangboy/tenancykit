package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The terminal outcome of one recipient's delivery, as the producer that requested it needs to see it.
 *
 * <p>Only {@code SUCCEEDED} and {@code DEAD} are terminal and only those are published: a transient attempt is an
 * internal step of this service's retry loop and reporting it would make a consumer's ledger flicker. {@code status},
 * {@code attempts} and {@code completedAt} are carried together because the consumer's rule is "keep the newest
 * completedAt", not "apply the last message".
 *
 * @param deliveryId the ledger row this outcome belongs to; it forms the event's aggregate
 * @param completedAt when the terminal status was reached, and the event's own timestamp
 * @param providerMessageId the transport's id when it supplied one; diagnostic only
 * @param lastError a short reason, never a body, an address or a credential
 */
public record DeliveryResult(
        TenantId tenantId,
        UUID deliveryId,
        String requestId,
        String recipient,
        DeliveryStatus status,
        int attempts,
        Instant completedAt,
        String providerMessageId,
        String lastError) {

    public DeliveryResult {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completedAt, "completedAt");
        if (status != DeliveryStatus.SUCCEEDED && status != DeliveryStatus.DEAD) {
            throw new IllegalArgumentException("only a terminal outcome is published, not " + status);
        }
    }

    public boolean succeeded() {
        return status == DeliveryStatus.SUCCEEDED;
    }
}

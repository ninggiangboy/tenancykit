package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailer.email.domain.delivery.EmailDelivery;
import dev.ngb.mailer.shared.Recipient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The accept side of the ledger: what the command consumer writes. It never sends anything, which is why a retried
 * command can never duplicate an email.
 */
public interface EmailDeliveryRepository {

    /**
     * Inserts one ledger row and its body, or nothing when the row already exists.
     *
     * @return true when a row was written
     */
    boolean addIfAbsent(
            EmailDelivery delivery,
            String replyTo,
            List<Recipient> cc,
            String html,
            String text,
            Map<String, String> headers);

    /**
     * The rows that already exist for this request, across the recipients named. It is what makes a repeated
     * {@code requestId} a defined answer instead of a silent drop.
     */
    List<EmailDelivery> findExisting(TenantId tenantId, String requestId, Collection<String> recipients);

    /** Brings a dead delivery back to {@code PENDING} with a fresh budget, the one case a repeated request must act on. */
    void resurrect(UUID id);
}

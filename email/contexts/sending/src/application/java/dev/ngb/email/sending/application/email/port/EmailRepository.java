package dev.ngb.email.sending.application.email.port;

import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.sending.domain.email.EmailStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The write side of the sent-email ledger. */
public interface EmailRepository {

    void insert(Email email);

    Optional<Email> findById(UUID emailId);

    Optional<Email> findByRequestId(String requestId);

    /**
     * Applies a terminal delivery outcome only when it is newer than the one already recorded.
     *
     * @return false when the email is unknown or the outcome is an out-of-order replay
     */
    boolean applyDelivery(String requestId, EmailStatus status, String reason, Instant completedAt);

    /** Removes the rendered bodies of emails older than the cut-off, at most {@code limit} rows. */
    int purgeBodies(Instant olderThan, Instant now, int limit);
}

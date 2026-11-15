package dev.ngb.mailer.email.application.email.port;

import dev.ngb.mailer.email.domain.delivery.DeliveryAttempt;
import dev.ngb.mailer.email.domain.delivery.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The delivery side of the ledger: claim, record, and the bounded deletes that keep retention a fact rather than an
 * intention.
 *
 * <p>The claim and the send cannot share a transaction, so the claim is committed first, the send happens outside
 * any transaction, and the outcome is written afterwards with a compare-and-swap on the attempt count.
 */
public interface DeliveryClaimRepository {

    /**
     * Claims up to {@code limit} due rows with {@code for update skip locked}, setting {@code IN_PROGRESS} and moving
     * {@code attempts} forward in the same statement.
     *
     * <p>The increment belongs here, not in the outcome: a process that dies after the provider accepted the message
     * but before the outcome commit would otherwise lose the increment and re-send forever without ever reaching the
     * budget. {@code PENDING} and {@code IN_PROGRESS} are both claimable, so a row a lost worker left behind comes
     * back once its lease runs out.
     */
    List<ClaimedDelivery> claimDue(Instant now, int limit, Duration lease);

    /**
     * Writes the attempt and the resulting status for one delivery.
     *
     * @param claimedAttempts the attempt count the claim wrote; the update matches on it, so a row another worker has
     *     taken over is left alone
     * @return the rows changed: 1 normally, 0 when another worker owns the row
     */
    int recordOutcome(
            UUID deliveryId,
            int claimedAttempts,
            DeliveryAttempt attempt,
            DeliveryStatus status,
            String providerMessageId,
            Instant nextAttemptAt,
            Instant completedAt);

    /**
     * Marks rows a lost worker left {@code IN_PROGRESS} past their budget as {@code DEAD}, so they stop being claimed.
     *
     * <p>It returns the rows it killed rather than a count: the caller publishes their terminal result in the same
     * transaction, and a delivery whose worker died on its final attempt would otherwise leave the producer waiting
     * forever. {@code completedAt} is the {@code now} the sweep wrote.
     */
    List<ExhaustedDelivery> killExhausted(Instant now, int maxAttempts);

    /** Deletes at most {@code limit} bodies created before {@code olderThan}. The ledger row survives, which is what keeps a dead delivery resendable. */
    int purgeBodies(Instant olderThan, int limit);

    /** Deletes at most {@code limit} inbox rows processed before {@code olderThan}, so the table does not grow without bound. */
    int purgeInbox(Instant olderThan, int limit);
}

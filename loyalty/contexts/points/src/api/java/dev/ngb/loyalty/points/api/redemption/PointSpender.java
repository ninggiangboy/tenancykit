package dev.ngb.loyalty.points.api.redemption;

import java.time.Instant;
import java.util.UUID;

/**
 * Spends a member's points against the member's own balance, for a caller that is already inside a transaction.
 *
 * <p>It is the one implementation of a spend: the redemption endpoint calls it and a caller that spends points as part
 * of a larger change calls it too, so the checks, the lot order and the idempotency rule are written once. The caller
 * owns the transaction and its commit; this function takes the member's row lock first and never opens one itself.
 */
public interface PointSpender {

    /**
     * Spends {@code points} of the member the reference names.
     *
     * @param externalRef the Tenant's reference for the member
     * @param idempotencyKey the caller's key for this spend, 1-200 characters
     * @param points the points to spend, at least one
     * @param reference the caller's own reference for the spend, or null
     * @throws PointSpendRefusal when the member does not exist, holds a debt, cannot cover the points, or the key was
     *     used before for a different request
     */
    Receipt spend(Command command);

    /** One spend as the caller asks for it. */
    record Command(String externalRef, String idempotencyKey, long points, String reference) {}

    /**
     * The redemption and the balance it left; {@code replayed} is true when the key had already spent these points.
     *
     * <p>{@code memberId} is the opaque id of the member the spend was made for, so a caller that owns per-member rows of
     * its own can name it without a second question.
     */
    record Receipt(
            UUID redemptionId,
            UUID memberId,
            String externalRef,
            long points,
            String reference,
            String status,
            long available,
            long debt,
            Instant createdAt,
            Instant reversedAt,
            boolean replayed) {}
}

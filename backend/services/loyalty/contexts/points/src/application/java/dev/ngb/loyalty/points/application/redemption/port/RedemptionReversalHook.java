package dev.ngb.loyalty.points.application.redemption.port;

import java.util.UUID;

/**
 * What a reversal does before the lots are restored, for a feature that must be undone with the spend.
 *
 * <p>The one implementation to come is vouchers: a reward redemption that issued a voucher voids it here, and refuses
 * the reversal when the voucher was already used. It runs inside the reversal's transaction, after the member's row is
 * locked and before anything is written, so a refusal leaves the redemption as it was.
 */
public interface RedemptionReversalHook {

    /** Called once per redemption being reversed, before its lots are restored. */
    void beforeReversal(UUID memberId, UUID redemptionId);
}

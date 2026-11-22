package dev.ngb.billing.money.api.charge;

import java.time.Instant;

/**
 * The one seam through which a discount reaches a charge. Money owns this port; the coupon context implements it, so
 * money depends on nothing in coupon and the arrow never turns back.
 *
 * <p>The three operations run at different moments of the charge transaction, and the difference matters:
 * <ul>
 *   <li>{@link #preview} is read-only, takes no locks and consumes nothing. It serves an estimate.</li>
 *   <li>{@link #claim} runs inside the charge transaction, after the wallet lock, and its quote is guaranteed
 *       consumable: the rows it read are locked until the transaction ends.</li>
 *   <li>{@link #consume} runs only when the charge succeeded, in that same transaction, and records what was taken.
 *       A failed charge and a replay call neither.</li>
 * </ul>
 */
public interface ChargeDiscounts {

    /** The discount a charge would receive, without taking any lock or consuming anything. */
    DiscountQuote preview(DiscountRequest request);

    /** The discount a charge will receive. Called once, inside the charge transaction, after the wallet lock. */
    DiscountQuote claim(DiscountRequest request);

    /**
     * Records that {@code quote}'s lines were applied to {@code chargeId}. {@code now} is the instant the quote was
     * claimed at, so a redemption that was usable then is still usable when it is recorded.
     *
     * @throws IllegalStateException when a line names a redemption that can no longer take it, which rolls the charge back
     */
    void consume(ChargeId chargeId, DiscountQuote quote, Instant now);

    /** The seam with no coupons behind it: every charge keeps its whole gross. */
    ChargeDiscounts NONE = new ChargeDiscounts() {
        @Override
        public DiscountQuote preview(DiscountRequest request) {
            return DiscountQuote.undiscounted(request.gross());
        }

        @Override
        public DiscountQuote claim(DiscountRequest request) {
            return DiscountQuote.undiscounted(request.gross());
        }

        @Override
        public void consume(ChargeId chargeId, DiscountQuote quote, Instant now) {
            // nothing is discounted, so nothing is consumed
        }
    };
}

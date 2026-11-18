package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import java.util.List;

/**
 * A failed charge (for example {@code insufficient_funds}) is a recorded outcome, not an exception.
 *
 * <p>{@code amount} is the net the wallet paid (or would have paid, for a failure); {@code grossAmount} is what the
 * charge was worth before coupons, and {@code discounts} are the lines in the order they applied. A succeeded charge
 * whose net is zero consumed its coupons and posted nothing.
 */
public record ChargeResult(
        ChargeId id, ChargeStatus status, String failureReason, Money amount, LedgerEntryId ledgerEntryId, boolean replayed,
        Money grossAmount, List<DiscountLine> discounts) {

    public ChargeResult {
        discounts = discounts == null ? List.of() : List.copyOf(discounts);
    }

    /** A result with no discounts, for a caller that only has a recorded charge to report. */
    public static ChargeResult of(ChargeId id, ChargeStatus status, String failureReason, Money amount, LedgerEntryId ledgerEntryId,
            boolean replayed) {
        return new ChargeResult(id, status, failureReason, amount, ledgerEntryId, replayed, amount, List.of());
    }
}

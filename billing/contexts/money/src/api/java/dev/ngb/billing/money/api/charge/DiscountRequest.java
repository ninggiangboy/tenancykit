package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * The question the discount seam is asked: what is still owed on this charge, at this instant. The gross is the
 * amount after pricing's single rounding and its minimum and maximum charge, which is exactly the base a discount is
 * computed on.
 */
public record DiscountRequest(WalletId walletId, Money gross, ChargeContext context, Instant now) {

    public DiscountRequest {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(now, "now");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("a discount is asked for a positive gross");
        }
    }
}

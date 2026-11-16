package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * What the coupons on a wallet take off a charge, and what is left. The lines are in the order they were applied,
 * so {@code gross - sum(lines) == net} always, and a net of zero means nothing is owed.
 */
public record DiscountQuote(Money gross, Money net, List<DiscountLine> lines) {

    public DiscountQuote {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(net, "net");
        lines = List.copyOf(lines);
        if (net.isNegative()) {
            throw new IllegalArgumentException("a net amount is never negative");
        }
        if (net.isGreaterThan(gross)) {
            throw new IllegalArgumentException("a net amount is never more than the gross");
        }
        Money total = Money.zero(gross.currency());
        for (DiscountLine line : lines) {
            total = total.plus(line.amount());
        }
        if (!total.equals(gross.minus(net))) {
            throw new IllegalArgumentException("the lines must sum to gross minus net");
        }
    }

    /** No coupon applied: the wallet pays the whole charge. */
    public static DiscountQuote undiscounted(Money gross) {
        return new DiscountQuote(gross, gross, List.of());
    }

    public boolean isDiscounted() {
        return !lines.isEmpty();
    }

    /** Everything was discounted, so nothing is posted and no invoice line is produced. */
    public boolean isFullyDiscounted() {
        return net.isZero() && gross.isPositive();
    }

    public Money discountTotal() {
        return gross.minus(net);
    }
}

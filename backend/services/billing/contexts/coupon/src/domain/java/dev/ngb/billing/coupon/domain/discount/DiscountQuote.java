package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.sharedkernel.money.Money;

import java.util.List;
import java.util.Objects;

/**
 * What a set of redemptions takes off a charge, and what is left to pay. The lines are in the order they were
 * applied, which is the order the Charge records them in, so the arithmetic reads top to bottom: gross, each line,
 * net.
 */
public record DiscountQuote(Money gross, Money net, List<AppliedDiscount> lines) {

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
        for (AppliedDiscount line : lines) {
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

    /** The whole charge was discounted, so the wallet pays nothing and no ledger entry is written. */
    public boolean isFullyDiscounted() {
        return net.isZero() && gross.isPositive();
    }

    public Money discountTotal() {
        return gross.minus(net);
    }
}

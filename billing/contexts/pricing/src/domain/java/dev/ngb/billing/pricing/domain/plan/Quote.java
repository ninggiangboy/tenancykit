package dev.ngb.billing.pricing.domain.plan;

import dev.ngb.billing.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.ngb.billing.pricing.domain.rule.PriceLine;

/**
 * A priced result. {@code amount} is what would be charged, in whole minor units; {@code exactAmount} is the
 * unrounded total of the lines; {@code adjustment} says when the minimum or maximum charge replaced the
 * calculated amount.
 */
public record Quote(Money amount, BigDecimal exactAmount, List<PriceLine> lines, Adjustment adjustment) {

    public enum Adjustment { NONE, MINIMUM_CHARGE, MAXIMUM_CHARGE }

    public Quote {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(exactAmount, "exactAmount");
        lines = List.copyOf(lines);
        Objects.requireNonNull(adjustment, "adjustment");
    }

    /**
     * What each metric contributed to the quote, rounded once to minor units. A line with no metric (a base or a start
     * fee) belongs to no metric and is left out. A metric-scoped discount uses this so it takes off only its own
     * metrics' share, and both the real charge and the estimate read it from here so they cannot disagree.
     */
    public Map<String, Money> metricAmounts() {
        Map<String, BigDecimal> exact = new HashMap<>();
        for (PriceLine line : lines) {
            if (line.metric() != null && !line.metric().isBlank()) {
                exact.merge(line.metric(), line.amount(), BigDecimal::add);
            }
        }
        Map<String, Money> amounts = new HashMap<>();
        exact.forEach((metric, amount) ->
                amounts.put(metric, Money.of(amount.setScale(0, RoundingMode.HALF_UP).longValueExact(), this.amount.currency())));
        return amounts;
    }
}

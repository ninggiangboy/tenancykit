package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.sharedkernel.money.Money;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What is still undiscounted of each metric's share of a charge, so a metric-scoped coupon touches only its own
 * metrics. It is built once per plan from the facts and reduced as discounts apply.
 *
 * <p>The subtotals never sum to more than the gross. When pricing's minimum or maximum charge replaced the calculated
 * amount, they are scaled down to fit but never up: the gross is the ceiling a coupon can discount.
 */
final class MetricSubtotals {

    private final Map<String, Money> remaining;

    private MetricSubtotals(Map<String, Money> remaining) {
        this.remaining = remaining;
    }

    static MetricSubtotals of(ChargeFacts facts) {
        Map<String, Money> amounts = new LinkedHashMap<>();
        facts.metricAmounts().forEach((metric, amount) -> amounts.put(metric, amount));
        if (amounts.isEmpty()) {
            return new MetricSubtotals(amounts);
        }
        Money total = Money.zero(facts.gross().currency());
        for (Money amount : amounts.values()) {
            total = total.plus(amount);
        }
        if (total.isGreaterThan(facts.gross())) {
            scaleDown(amounts, facts.gross());
        }
        return new MetricSubtotals(amounts);
    }

    /** The share of the coupon's own metrics that is still undiscounted, never more than the running net. */
    Money baseFor(Iterable<String> metrics, Money runningNet) {
        Money subtotal = Money.zero(runningNet.currency());
        for (String metric : metrics) {
            Money left = remaining.get(metric);
            if (left != null) {
                subtotal = subtotal.plus(left);
            }
        }
        return subtotal.isLessThan(runningNet) ? subtotal : runningNet;
    }

    /**
     * Takes {@code taken} off the coupon's metrics, pro rata to what is left of each, so no subtotal goes negative.
     * Floor division leaves a remainder that goes to the first metric alphabetically, which keeps the result
     * deterministic.
     */
    void reduce(Iterable<String> metrics, Money taken) {
        TreeSet<String> ordered = new TreeSet<>();
        metrics.forEach(metric -> {
            if (remaining.containsKey(metric) && remaining.get(metric).isPositive()) {
                ordered.add(metric);
            }
        });
        if (ordered.isEmpty() || !taken.isPositive()) {
            return;
        }
        Money total = Money.zero(taken.currency());
        for (String metric : ordered) {
            total = total.plus(remaining.get(metric));
        }
        if (total.isZero()) {
            return;
        }
        long toReduce = Math.min(taken.minorUnits(), total.minorUnits());
        long assigned = 0;
        for (String metric : ordered) {
            long share = remaining.get(metric).minorUnits() * toReduce / total.minorUnits();
            assigned += share;
            remaining.put(metric, Money.of(remaining.get(metric).minorUnits() - share, taken.currency()));
        }
        long leftover = toReduce - assigned;
        for (String metric : ordered) {
            if (leftover <= 0) {
                break;
            }
            long current = remaining.get(metric).minorUnits();
            long give = Math.min(current, leftover);
            remaining.put(metric, Money.of(current - give, taken.currency()));
            leftover -= give;
        }
    }

    Money remainingTotal() {
        Money total = null;
        for (Money amount : remaining.values()) {
            total = total == null ? amount : total.plus(amount);
        }
        return total == null ? null : total;
    }

    /** Shrinks each subtotal proportionally so they sum to {@code ceiling}, with the remainder given alphabetically. */
    private static void scaleDown(Map<String, Money> amounts, Money ceiling) {
        TreeSet<String> ordered = new TreeSet<>(amounts.keySet());
        Money total = Money.zero(ceiling.currency());
        for (String metric : ordered) {
            total = total.plus(amounts.get(metric));
        }
        if (total.isZero()) {
            amounts.replaceAll((metric, amount) -> Money.zero(ceiling.currency()));
            return;
        }
        long scaled = 0;
        for (String metric : ordered) {
            long share = amounts.get(metric).minorUnits() * ceiling.minorUnits() / total.minorUnits();
            scaled += share;
            amounts.put(metric, Money.of(share, ceiling.currency()));
        }
        long leftover = ceiling.minorUnits() - scaled;
        for (String metric : ordered) {
            if (leftover <= 0) {
                break;
            }
            amounts.put(metric, Money.of(amounts.get(metric).minorUnits() + 1, ceiling.currency()));
            leftover--;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MetricSubtotals subtotals && subtotals.remaining.equals(remaining);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remaining);
    }
}

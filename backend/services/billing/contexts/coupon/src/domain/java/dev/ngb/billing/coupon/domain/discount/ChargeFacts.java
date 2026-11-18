package dev.ngb.billing.coupon.domain.discount;

import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The facts of one charge as the discount engine sees them. The domain defines its own type for this so it never
 * names a type from another context: the application layer maps the money port's request onto it.
 *
 * <p>{@code planId} is present only when the caller knows it (usage rating and subscription renewal). {@code
 * metricAmounts} is present only for a usage charge, and empty until pricing supplies the breakdown. A coupon with a
 * filter for a fact that is absent never matches, which is what keeps a plan or metric scoped coupon off a
 * standalone charge and a reservation capture.
 */
public record ChargeFacts(ChargeKind kind, UUID planId, Money gross, Map<String, Money> metricAmounts) {

    public ChargeFacts {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(gross, "gross");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException("a charge's gross must be positive");
        }
        metricAmounts = metricAmounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metricAmounts));
        for (Map.Entry<String, Money> entry : metricAmounts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("a metric amount needs a metric name");
            }
            Objects.requireNonNull(entry.getValue(), "metric amount");
            if (!entry.getValue().currency().equals(gross.currency())) {
                throw new IllegalArgumentException("currency mismatch: gross is %s, metric %s is %s".formatted(
                        gross.currency(), entry.getKey(), entry.getValue().currency()));
            }
        }
    }

    public static ChargeFacts of(ChargeKind kind, Money gross) {
        return new ChargeFacts(kind, null, gross, Map.of());
    }

    public static ChargeFacts of(ChargeKind kind, UUID planId, Money gross) {
        return new ChargeFacts(kind, planId, gross, Map.of());
    }

    public boolean hasPlan() {
        return planId != null;
    }

    public boolean hasMetrics() {
        return !metricAmounts.isEmpty();
    }
}

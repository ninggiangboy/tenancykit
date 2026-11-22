package dev.ngb.billing.money.api.charge;

import dev.ngb.billing.sharedkernel.money.Money;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the caller of a charge knows about it beyond the amount: the kind, the plan it was priced under when the
 * caller prices under one, and what each metric contributed when the caller has that breakdown.
 *
 * <p>Only usage rating and subscription renewal fill in a plan; only usage rating fills in metric amounts. A coupon
 * scoped to a plan or a metric therefore never matches a standalone charge or a reservation capture, which carry
 * neither.
 */
public record ChargeContext(ChargeKind kind, UUID planId, Map<String, Money> metricAmounts) {

    public ChargeContext {
        if (kind == null) {
            throw new IllegalArgumentException("a charge context names its kind");
        }
        metricAmounts = metricAmounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metricAmounts));
    }

    public static ChargeContext of(ChargeKind kind) {
        return new ChargeContext(kind, null, Map.of());
    }

    public static ChargeContext of(ChargeKind kind, UUID planId) {
        return new ChargeContext(kind, planId, Map.of());
    }
}

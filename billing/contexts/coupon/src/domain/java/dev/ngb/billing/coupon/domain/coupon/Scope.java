package dev.ngb.billing.coupon.domain.coupon;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which charges of a wallet a coupon may discount. An empty set in a dimension means "any" in that dimension, which
 * is why a coupon with no filters applies to every charge of the wallet.
 *
 * <p>A plan or metric filter can only match a charge whose caller supplies that fact. Usage rating and subscription
 * renewal supply the plan; only usage rating supplies metric amounts. A reservation capture and a standalone charge
 * supply neither, so a coupon with either filter never applies to them.
 */
public record Scope(Set<ChargeKind> kinds, Set<UUID> planIds, Set<String> metrics) {

    public Scope {
        kinds = sortedCopy(kinds, "kinds");
        planIds = sortedCopy(planIds, "planIds");
        metrics = sortedCopy(metrics, "metrics");
    }

    /** A coupon that applies to every charge of the wallet. */
    public static Scope everything() {
        return new Scope(Set.of(), Set.of(), Set.of());
    }

    public boolean matchesAnyKind() {
        return kinds.isEmpty();
    }

    public boolean isPlanScoped() {
        return !planIds.isEmpty();
    }

    public boolean isMetricScoped() {
        return !metrics.isEmpty();
    }

    private static <T> Set<T> sortedCopy(Set<T> values, String name) {
        if (values == null) {
            return Set.of();
        }
        if (values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}

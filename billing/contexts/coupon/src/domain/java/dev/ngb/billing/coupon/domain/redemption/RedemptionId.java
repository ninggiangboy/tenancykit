package dev.ngb.billing.coupon.domain.redemption;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a Redemption. A value object so a raw UUID is never threaded through business code. */
public record RedemptionId(UUID value) implements Comparable<RedemptionId> {

    public RedemptionId {
        Objects.requireNonNull(value, "value");
    }

    public static RedemptionId of(String text) {
        return new RedemptionId(UUID.fromString(text));
    }

    @Override
    public int compareTo(RedemptionId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

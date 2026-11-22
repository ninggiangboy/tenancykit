package dev.ngb.loyalty.rewards.domain.reward;

import java.util.Objects;
import java.util.UUID;

/** A reward's own id, stable across updates so a voucher can name the reward it was issued for. */
public record RewardId(UUID value) {

    public RewardId {
        Objects.requireNonNull(value, "value");
    }

    public static RewardId of(String text) {
        return new RewardId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

package dev.ngb.loyalty.rewards.application.reward.query;

import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A catalog entry as the read side answers it. {@code active} is the wire shorthand for the stored status: a caller
 * filters and reads the catalog with a boolean, and the three-state detail stays visible beside it.
 */
public record RewardView(
        UUID rewardId,
        String name,
        String description,
        long pointsCost,
        Long stock,
        boolean active,
        RewardStatus status,
        Instant validFrom,
        Instant validTo,
        int voucherValidDays,
        long version) {}

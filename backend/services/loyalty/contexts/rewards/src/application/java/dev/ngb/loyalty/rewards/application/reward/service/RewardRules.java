package dev.ngb.loyalty.rewards.application.reward.service;

import dev.ngb.loyalty.rewards.application.reward.exception.RewardRefusal;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardStatus;
import java.time.Instant;
import java.util.Locale;

/**
 * The rules a catalog entry obeys: a name, a cost of points, an optional stock, a window that does not end before it
 * starts, and a voucher lifetime of at least a day.
 *
 * <p>The cost is at least one point because a redemption spends it through the one spend function, and a spend of no
 * points is not a spend: the redemption row a voucher names is a spend, and the points context refuses and cannot store
 * one of zero. A reward a Tenant hands out for nothing is not a catalog entry — it has nothing to exchange.
 */
public final class RewardRules {

    public static final int MAX_NAME = 255;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int DEFAULT_VOUCHER_VALID_DAYS = 30;

    private RewardRules() {}

    /** @return the name as stored: trimmed, 1 to {@value #MAX_NAME} characters */
    public static String name(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_NAME) {
            throw RewardRefusal.invalid("name is 1 to " + MAX_NAME + " characters.");
        }
        return value;
    }

    /** @return the description as stored, which may be absent */
    public static String description(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DESCRIPTION) {
            throw RewardRefusal.invalid("description has at most " + MAX_DESCRIPTION + " characters.");
        }
        return value;
    }

    public static long pointsCost(Long raw) {
        if (raw == null || raw < 1) {
            throw RewardRefusal.invalid("pointsCost is required and is at least 1.");
        }
        return raw;
    }

    public static Long stock(Long raw) {
        if (raw != null && raw < 0) {
            throw RewardRefusal.invalid("stock is null for unlimited or at least 0.");
        }
        return raw;
    }

    public static int voucherValidDays(Integer raw) {
        if (raw == null) {
            return DEFAULT_VOUCHER_VALID_DAYS;
        }
        if (raw < 1) {
            throw RewardRefusal.invalid("voucherValidDays is at least 1.");
        }
        return raw;
    }

    /** @return the window, refused when it ends before it starts */
    public static Instant[] window(Instant from, Instant to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw RewardRefusal.invalid("the validity window ends before it starts.");
        }
        return new Instant[] {from, to};
    }

    /** @return the resolved status; {@code active} is the wire shorthand for {@code ACTIVE} or {@code ARCHIVED} */
    public static RewardStatus status(String status, Boolean active) {
        if (status != null && !status.isBlank()) {
            try {
                return RewardStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw RewardRefusal.invalid("status is ACTIVE or ARCHIVED.");
            }
        }
        if (active == null) {
            return RewardStatus.ACTIVE;
        }
        return active ? RewardStatus.ACTIVE : RewardStatus.ARCHIVED;
    }

    /** Refuses a redemption of a reward the catalog no longer offers, before anything is written. */
    public static void requireRedeemable(Reward reward, Instant now) {
        if (!reward.redeemableAt(now)) {
            throw RewardRefusal.invalid(
                    "REWARD_NOT_AVAILABLE", "The reward is not active or is outside its validity window.");
        }
    }
}

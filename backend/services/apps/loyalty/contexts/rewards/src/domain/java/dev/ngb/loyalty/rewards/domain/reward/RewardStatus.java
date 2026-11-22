package dev.ngb.loyalty.rewards.domain.reward;

/**
 * The life of a catalog entry. An active reward may be redeemed inside its window; an archived one is kept as history
 * and can no longer be redeemed, which is how a catalog is retired without deleting the vouchers that name it.
 */
public enum RewardStatus {
    ACTIVE,
    ARCHIVED
}

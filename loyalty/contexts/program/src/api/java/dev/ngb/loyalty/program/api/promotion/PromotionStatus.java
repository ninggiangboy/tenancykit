package dev.ngb.loyalty.program.api.promotion;

/** Where a promotion stands. Only an {@link #ACTIVE} promotion is considered by the earn engine. */
public enum PromotionStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED
}

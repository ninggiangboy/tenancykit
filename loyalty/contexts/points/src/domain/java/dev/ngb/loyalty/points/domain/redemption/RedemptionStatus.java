package dev.ngb.loyalty.points.domain.redemption;

/** Whether a redemption still holds the points it spent, or had them restored by a reversal. */
public enum RedemptionStatus {
    /** The points are spent. */
    ACTIVE,
    /** The points were restored to the lots the redemption consumed. */
    REVERSED
}

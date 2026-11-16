package dev.ngb.billing.coupon.domain.redemption;

/** Why a redemption stopped being ACTIVE. It is part of the event a Tenant reads, so the set is closed. */
public enum EndReason {
    /** The application cap, the total-discount cap or the end time was reached. */
    EXHAUSTED,
    /** An administrator revoked it. */
    REVOKED,
    /** The coupon was deactivated with its running redemptions revoked. */
    COUPON_DEACTIVATED,
    /** A wallet-credit coupon was redeemed: its one benefit was handed over at once. */
    GRANTED
}

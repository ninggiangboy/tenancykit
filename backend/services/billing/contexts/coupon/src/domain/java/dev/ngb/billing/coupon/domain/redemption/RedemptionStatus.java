package dev.ngb.billing.coupon.domain.redemption;

/**
 * The life of one redemption. ACTIVE is the only state that a charge may consume; ENDED and REVOKED are terminal.
 * A wallet-credit coupon is created ENDED, because its benefit is handed over once at redeem rather than applied to
 * charges later.
 */
public enum RedemptionStatus {
    ACTIVE,
    ENDED,
    REVOKED
}

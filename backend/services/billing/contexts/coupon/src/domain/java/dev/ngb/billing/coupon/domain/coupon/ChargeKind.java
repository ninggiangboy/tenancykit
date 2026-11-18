package dev.ngb.billing.coupon.domain.coupon;

/**
 * Which charge a coupon applies to, from the point of view of the caller that records it. Usage rating and
 * subscription renewal know their plan; a reservation capture and a standalone charge do not, which is why a plan
 * or metric filter can never match them.
 */
public enum ChargeKind {
    USAGE,
    SUBSCRIPTION_RENEWAL,
    RESERVATION,
    STANDALONE
}

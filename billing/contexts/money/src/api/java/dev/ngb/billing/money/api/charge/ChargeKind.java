package dev.ngb.billing.money.api.charge;

/** Which charge is being discounted, as the caller knows it. */
public enum ChargeKind {
    USAGE,
    SUBSCRIPTION_RENEWAL,
    RESERVATION,
    STANDALONE
}
